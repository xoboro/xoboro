# 0097 — Reading PDF XMP metadata

## Status

Accepted.

## Context

`PdfMetadataProvider` read only the document information dictionary. Its own KDoc recorded why XMP was
left out: "reading both raises a precedence question that no observed file has yet forced." The coverage
row carried "XMP metadata is still unread" as the remaining work.

The precedence question is real, and answering it "XMP wins" or "the dictionary wins" would both be
wrong, because the two sources differ **in kind**, not only in value:

- The dictionary's `Author` and `Keywords` are single free-text strings. Splitting them into people and
  tags requires guessing a separator, and `PdfMetadataProvider` already documented that `"Doe, Jane"`
  is split wrongly as the unavoidable cost.
- XMP's `dc:creator` and `dc:subject` are **ordered lists of items**. There is nothing to guess.

## Decision

### Per-field precedence, not one winner

- **`authors` and `tags`: XMP wins when present.** Not because XMP is more authoritative, but because a
  structured list removes a documented error. This is the one place where preferring XMP fixes something
  rather than choosing between two equally good values.
- **Everything else: the dictionary wins, XMP fills gaps.** The dictionary is what a producer most
  recently touched in the common case, XMP is routinely a stale template left over from an export, and
  gap-filling cannot change a value a library has already imported.

`dc:language` is read by nothing. `BookMetadataPatch` has no language field, because in this catalog
language belongs to a series and a PDF has no series (ADR: a PDF's series would be invented). Storing it
nowhere is better than inventing a series to hold it.

### Parsed with jsoup, not by adding xmpbox

`org.apache.pdfbox:xmpbox` is the official parser, and it was declined. XMP is RDF/XML, the five fields
read here are ordinary elements in it, and jsoup is already a dependency of this module for the EPUB
package document. A new dependency to read five fields would have to earn itself.

### Every read is lenient, and failure is absence

XMP in the wild is routinely malformed, truncated mid-element, or a stale export template. A packet that
cannot be parsed yields an empty snapshot rather than an error: **metadata that fails to parse must
never be the reason a book fails to import**, and the dictionary metadata beside it must still land.

Two narrower decisions follow the same principle:

- A `rdf:Alt` is resolved to `x-default` when present, else the first entry. Xoboro stores one title, so
  an alternative has to be chosen; `x-default` is the producer's own statement of which, and falling
  back to the first is a bounded guess — a title in the wrong language beats no title.
- `xmp:CreateDate` is truncated to its date part and rejected unless that part is an ISO date. An XMP
  timestamp carries a time and offset that would be invented precision for "when was this published".
- A bare value where a container was expected is treated as **one item**, not split. The entire reason
  to read XMP for creators and subjects is that it does not require guessing where one value ends;
  splitting the degenerate case would reintroduce exactly that.

## Consequences

- A PDF carrying only XMP now imports. Previously it showed a filename-derived title.
- A PDF carrying both keeps its dictionary title and gains whatever the dictionary lacked.
- The `"Doe, Jane"` splitting error no longer applies to any PDF that ships `dc:creator`. It still
  applies to a PDF with only a dictionary, and `PdfMetadataProvider.splitNames` still says so.
- Verified by mutation, and one of those mutations found a **vacuous test**: the first author-precedence
  fixture used `"Doe, Jane and Roe, John"`, which the dictionary's heuristic happens to split into
  exactly the same two names XMP carries — so swapping the precedence passed. The fixture is now
  `"Doe, Jane, Roe, John"`, which the dictionary splits into four people, and the assertion detects the
  swap. Reversing the precedence and disabling XMP entirely both fail now.
