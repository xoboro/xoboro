#!/usr/bin/env python3
"""Does the search engine find the thing you asked for?

Row counts only show that search *answers*. A query returning 77,360 rows says nothing
about whether it returned the right ones, and that is the mistake every check of this
engine had made so far: non-zero was read as working.

This asks two different questions per sampled title, because conflating them is how a
healthy index gets reported as broken.

**Recall** — is the entity the fragment was cut from actually returned? This is a direct
membership test and it is the metric that matters: the id is either in the answer or it is
not.

A second, weaker number is also reported. ``at_least_substring`` compares the API's
``totalItems`` against an ``instr`` count over titles. It is a *lower bound only*, and it
is labelled that way because it cannot fail for the reason one might expect: the trigram
index concatenates title, sort title, series title and alternate titles into one indexed
column (``V32__catalog_title_substring_search.sql``), so its match set is a superset of a
title-only substring test and the inequality is nearly always satisfied. Reading it as
"recall is fine" would be reading a tautology.

**What settles it** is that an interior fragment returns anything at all. A word index
cannot match a fragment starting inside a word, so a non-empty answer for an interior cut
can only have come from the trigram index — and a wrong or absent trigram index shows up
here as ``returned_nothing``, not as a small number.

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
DOCKER = os.environ.get("DOCKER", "/Users/rilacc/.orbstack/bin/docker")
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
    # A long window is rejected far more often than a short one - most titles carry a
    # space within eight characters - so the pool has to be much deeper or the sample comes
    # back too small to claim anything from. An n of 2 is not a measurement.
    limit = SAMPLE * 400 if single_token else SAMPLE
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
    # First-page membership only means something when the answer is small enough for a
    # first page to be most of it. Printing the median result size is what lets a reader of
    # this output tell "the engine found it" from "the query happened to be narrow".
    sizes: list[int] = []
    failures: dict[str, list[str]] = {}
    for entity_id, fragment in pairs:
        checked += 1
        total, ids = search(bearer, scope, fragment)
        sizes.append(total)
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
    # The headline is the membership test, not the inequality.
    ratio = f"{(on_page / checked * 100):.1f}%" if checked else "n/a"
    ordered = sorted(sizes)
    median = ordered[len(ordered) // 2] if ordered else 0
    print(
        f"{label:<26} checked={checked:<3} FOUND_ITSELF={on_page:<3} ({ratio:>6}) "
        f"returned_nothing={empty:<3} median_matches={median:<6} "
        f"at_least_substring={recall_ok:<3} below_substring={recall_short:<3}"
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

    # A longer interior cut, which is the control for selectivity rather than for the index.
    # Three characters out of 145,105 items matches thousands of them, so an entity below
    # the first page says the query was ambiguous, not that the engine lost it. Eight
    # characters is specific enough that first-page membership means something.
    selective = "substr(trim(m.title), 2, 8)"
    measure(bearer, "series_interior_long", "series", "series", "series_metadata", "series_id", selective, True)
    measure(bearer, "item_interior_long", "media-items", "book", "book_metadata", "book_id", selective, True)

    # Without a negative control, an engine that answered "everything" for every query
    # would score a perfect recall above and look ideal.
    for nonsense in ("zzqxjvw", "qqzzxxjj", "龘齾齉"):
        for scope in ("series", "media-items"):
            total, _ = search(bearer, scope, nonsense)
            print(f"negative_control scope={scope:<12} totalItems={total}")


if __name__ == "__main__":
    # A bare traceback is the one way this could print library data: an exception's
    # rendering can carry the value that caused it, and every value handled here is a
    # title or a fragment of one. The class and where it came from are enough to debug
    # with, and neither can contain a title.
    try:
        main()
    except Exception as failure:  # noqa: BLE001 - the point is that nothing escapes
        import traceback

        frames = traceback.extract_tb(failure.__traceback__)
        where = f"{frames[-1].name}:{frames[-1].lineno}" if frames else "unknown"
        print(f"failed kind={type(failure).__name__} at={where}")
        raise SystemExit(1) from None
