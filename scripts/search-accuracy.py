#!/usr/bin/env python3
"""Does the search engine find the thing you asked for?

Row counts only show that search *answers*. A query returning 77,360 rows says nothing
about whether it returned the right ones, and that is the mistake every check of this
engine had made so far: non-zero was read as working.

This asks two different questions per sampled title, because conflating them is how a
healthy index gets reported as broken.

**Recall** — does the index find everything a plain substring test finds? Answered by
comparing the API's ``totalItems`` against an ``instr`` count over the same titles,
computed in SQL. If the API returns at least as many, the index is losing nothing.

**Rank** — is the entity the fragment came from on the first page? A three-character
fragment can legitimately match thousands of titles, so an entity below the first page is
not evidence of anything wrong. Reported separately, never as a failure.

Fragments are cut from the **interior** of a title on purpose: a prefix is answered by the
word index, and only an interior fragment exercises the trigram index V32 added so Korean
titles could be found by a fragment inside them.

Library data never leaves the host. Titles are read inside SQL, held in memory, and used
only to build a URL and a count query. Nothing printed contains a title, a fragment, a
summary, an author or a path — the output is counts and ratios.

Usage:
    ssh <host> 'python3 -' < scripts/search-accuracy.py
"""

from __future__ import annotations

import json
import os
import subprocess
import urllib.parse
import urllib.request

BASE = os.environ.get("XOBORO_BASE", "http://127.0.0.1:25610")
CONTAINER = os.environ.get("XOBORO_CONTAINER", "xoboro-xoboro-1")
VOLUME = os.environ.get("XOBORO_VOLUME", "xoboro_xoboro-config")
ADMIN = os.environ.get("XOBORO_ADMIN", "admin@hongyoungjun.com")
DOCKER = os.environ.get("DOCKER", "docker")
SAMPLE = int(os.environ.get("SAMPLE", "25"))
PAGE = int(os.environ.get("PAGE", "200"))
API = f"{BASE}/api/xoboro/v1"

# One container for every query. Starting one per fragment would spend most of the run in
# `apk add`, and there are three fragments per sampled title.
SQLITE = [
    DOCKER, "run", "--rm", "-i", "-v", f"{VOLUME}:/c", "alpine:3", "sh", "-lc",
    # A real tab, not the two characters `\t`: inside double quotes `sh` keeps a backslash
    # literal, so writing it as an escape gives sqlite3 a separator no split will find and
    # every row comes back as one field.
    'apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 -separator "' + "\t" + '" /c/xoboro.sqlite',
]


def sql(script: str) -> list[list[str]]:
    """Runs a script and returns its rows. The script may contain titles; its output is
    returned to the caller and never printed by this function."""
    done = subprocess.run(SQLITE, input=script, capture_output=True, text=True, check=True)
    return [line.split("\t") for line in done.stdout.splitlines() if line.strip()]


def literal(value: str) -> str:
    """A SQL string literal. Titles contain apostrophes; a naive f-string would produce a
    syntax error for those and, worse, would only do so for some of the sample."""
    return "'" + value.replace("'", "''") + "'"


def token() -> str:
    password = subprocess.run(
        [DOCKER, "exec", CONTAINER, "cat", "/config/initial-admin-password"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    body = json.dumps({"email": ADMIN, "password": password, "transport": "BEARER"}).encode()
    request = urllib.request.Request(
        f"{API}/session", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=30) as answer:
        return json.load(answer)["accessToken"]


def search(bearer: str, scope: str, fragment: str) -> tuple[int, list[str]]:
    url = f"{API}/{scope}?query={urllib.parse.quote(fragment)}&size={PAGE}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {bearer}"})
    with urllib.request.urlopen(request, timeout=120) as answer:
        payload = json.load(answer)
    return payload.get("totalItems", -1), [item.get("id") for item in payload.get("items", [])]


def samples(
    table: str,
    meta: str,
    id_column: str,
    cut: str,
    single_token: bool = False,
) -> list[tuple[str, str]]:
    """Sampled `(id, fragment)` pairs.

    `single_token` keeps only fragments with no space and no punctuation in them. That is
    not a convenience: a trigram index tokenises on separators, so a three-character window
    straddling a word boundary is not one token and cannot be found as one. Measuring it
    against `instr` — which does not care about boundaries — makes a correct index look like
    it is losing rows, which is exactly how this check first read 20% on items.
    """
    # Over-sampled, because the filter below rejects candidates and the point is to end up
    # with SAMPLE of them rather than SAMPLE minus however many straddled a space.
    limit = SAMPLE * 20 if single_token else SAMPLE
    rows = sql(
        f"SELECT e.id, {cut} FROM {table} e JOIN {meta} m ON m.{id_column} = e.id "
        f"WHERE e.deleted_at_ms IS NULL AND length(trim(m.title)) >= 5 "
        f"ORDER BY e.id LIMIT {limit};"
    )
    pairs = [(row[0], row[1]) for row in rows if len(row) >= 2 and row[1].strip()]
    if single_token:
        pairs = [pair for pair in pairs if pair[1].isalnum()]
    return pairs[:SAMPLE]


def substring_count(table: str, meta: str, id_column: str, fragment: str) -> int:
    rows = sql(
        f"SELECT count(*) FROM {table} e JOIN {meta} m ON m.{id_column} = e.id "
        f"WHERE e.deleted_at_ms IS NULL AND instr(m.title, {literal(fragment)}) > 0;"
    )
    return int(rows[0][0]) if rows else -1


def shape(fragment: str) -> str:
    """What kind of fragment this is, as properties rather than as its value.

    A failure has to be characterised before it can be explained, and the fragment itself
    is library data. Length, script and whether it straddles a separator are the facts
    that distinguish "the index lost a row" from "this was never one token".
    """
    import unicodedata

    scripts = set()
    for character in fragment:
        if character.isspace():
            scripts.add("space")
        elif not character.isalnum():
            scripts.add("punct")
        elif "HANGUL" in unicodedata.name(character, ""):
            scripts.add("hangul")
        elif "CJK" in unicodedata.name(character, ""):
            scripts.add("cjk")
        elif character.isdigit():
            scripts.add("digit")
        else:
            scripts.add("latin")
    return f"len={len(fragment)} kinds={'+'.join(sorted(scripts))}"


def measure(bearer, label, scope, table, meta, id_column, cut, single_token=False):
    pairs = samples(table, meta, id_column, cut, single_token)
    checked = recall_ok = recall_short = empty = on_page = 0
    failures: dict[str, list[str]] = {}
    for entity_id, fragment in pairs:
        checked += 1
        total, ids = search(bearer, scope, fragment)
        expected = substring_count(table, meta, id_column, fragment)
        if entity_id in ids:
            on_page += 1
        if total == 0:
            empty += 1
            failures.setdefault("returned_nothing", []).append(shape(fragment))
        elif total < expected:
            recall_short += 1
            failures.setdefault("recall_short", []).append(
                f"{shape(fragment)} api={total} substring={expected}"
            )
        else:
            recall_ok += 1
    ratio = f"{(recall_ok / checked * 100):.1f}%" if checked else "n/a"
    print(
        f"{label:<26} checked={checked:<3} recall_ok={recall_ok:<3} "
        f"recall_short={recall_short:<3} returned_nothing={empty:<3} "
        f"on_first_page={on_page:<3} recall_ratio={ratio}"
    )
    for kind, shapes in sorted(failures.items()):
        counted: dict[str, int] = {}
        for entry in shapes:
            counted[entry] = counted.get(entry, 0) + 1
        for entry, count in sorted(counted.items()):
            print(f"    {kind}: {entry} x{count}")
    return {"checked": checked, "recall_ok": recall_ok, "short": recall_short, "empty": empty}


def main() -> None:
    bearer = token()
    print("login=ok")

    interior = "substr(trim(m.title), 2, 3)"
    # The first whole word, which is what the word index answers rather than the trigram one.
    leading = (
        "CASE WHEN instr(trim(m.title), ' ') > 0 "
        "THEN substr(trim(m.title), 1, instr(trim(m.title), ' ') - 1) "
        "ELSE trim(m.title) END"
    )

    # Any interior window, separators included. Kept because it is what a reader actually
    # types, and because the difference between the two rows below is the whole finding.
    measure(bearer, "series_interior_any", "series", "series", "series_metadata", "series_id", interior)
    measure(bearer, "item_interior_any", "media-items", "book", "book_metadata", "book_id", interior)
    # The same cut, restricted to windows that are one token. This is the row that says
    # whether the trigram index is correct.
    measure(bearer, "series_interior_1token", "series", "series", "series_metadata", "series_id", interior, True)
    measure(bearer, "item_interior_1token", "media-items", "book", "book_metadata", "book_id", interior, True)
    measure(bearer, "series_leading_word", "series", "series", "series_metadata", "series_id", leading)
    measure(bearer, "item_leading_word", "media-items", "book", "book_metadata", "book_id", leading)

    # Without a negative control, an engine that answered "everything" for every query
    # would score a perfect recall above and look ideal.
    for nonsense in ("zzqxjvw", "qqzzxxjj", "龘齾齉"):
        for scope in ("series", "media-items"):
            total, _ = search(bearer, scope, nonsense)
            print(f"negative_control scope={scope:<12} totalItems={total}")


if __name__ == "__main__":
    main()
