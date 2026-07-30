# ADR 0089: Encrypted media policy

- Status: accepted
- Date: 2026-07-30

## Context

Every analyzer treated an encrypted file as a broken one. A password-protected CBZ, a CBR whose
entries carry the password flag, a PDF that needs a user password, and an EPUB whose text is under
DRM all produced `MediaStatus.ERROR` with `ERR_1008`, the code for a container that could not be
opened or walked. Two consequences:

- An operator could not tell "replace this file" from "the storage hiccuped, try again", and a
  client could not tell a transient failure from a permanent one. Both retried forever.
- The EPUB case was worse than vague. An ADEPT-style publication keeps `container.xml` and the
  package document in plaintext, so analysis succeeded and the book was indexed `READY` with a page
  count and positions. The catalog entry looked healthy and no reader could render its text.

RAR was the sharpest illustration. junrar names its failure modes precisely -
`UnsupportedRarEncryptedException`, `WrongPasswordException`, `MissingNextVolumeException` - and the
analyzer caught the shared supertype and collapsed all of them into `ERR_1008`. The library knew what
was wrong and the answer was discarded.

## Decision

- Encrypted media is `MediaStatus.UNSUPPORTED` with the comment code `ERR_1101`, never `ERROR`. The
  status distinction is the point: `ERROR` invites a retry, `UNSUPPORTED` says the file itself has to
  change first. Media delivery already refuses anything that is not `READY`, so this opens no hole.
- Comment codes live in one place, `MediaAnalysisComment`. `ERR_10xx` stays reserved for codes Komga
  defines, so an imported library keeps its diagnoses (ADR 0009); `ERR_1100` and up are Xoboro's.
  Without that split, adopting a future Komga code could silently reinterpret one of ours.
- ZIP encryption is read from the central directory's general-purpose flags, not from the
  `ZipException` message. The JDK rejects an encrypted archive when it is opened and describes the
  cause only in message text; branching on that text would work today and break silently on a JDK
  that rewords it, falling back to the very code this replaces. The probe runs only after opening has
  already failed.
- A RAR failure is sorted by `RarFailureClassifier` into encrypted, incomplete volume set, or
  unreadable. An archive-level failure raised while reading one entry is rethrown rather than filed
  as "that entry would not decode": a password or a missing volume makes every read fail, so burying
  it there reported an intact archive as one with no usable pages.
- A PDF that needs a **user** password is unsupported. A PDF encrypted with only an **owner**
  password opens on the empty user password - that empty password is the credential its author chose
  to grant - so it is analyzed and served like any other document. Owner restrictions describe what a
  conforming viewer should offer; treating them as an access barrier would hide readable books from
  the person who owns them.
- An EPUB is unsupported when `META-INF/encryption.xml` covers a **spine** resource under an
  algorithm other than the two standard font-obfuscation ones. That file is not a DRM marker on its
  own: the same mechanism carries font obfuscation, which leaves text perfectly readable. Both halves
  of the rule are needed, and each is pinned by its own test.
- Page delivery answers `409 media_unsupported` instead of `409 media_not_ready` for unsupported
  media. The status class stays 409 - in both cases the obstacle is the state of the resource, not
  the request - while the code lets a client stop retrying.

## Consequences

An encrypted file now reports what is wrong with it, and a DRM-protected EPUB stops masquerading as
a healthy catalog entry. `MediaStatus.UNSUPPORTED` gets its first real use; nothing filters analysis
by status, so replacing the file still triggers a fresh analysis through the normal change detection.

Multipart RAR is only partly addressed. The classification of `MissingNextVolumeException` and
`MissingPreviousVolumeException` to `ERR_1102` is unit-tested against real exception instances, and
the analyzer's handling of an archive-level failure is tested through the Tika seam. What is **not**
verified is that junrar raises those exceptions for a real incomplete volume set: no dependency can
write a multi-volume RAR, no `rar` binary is assumed present, and a hand-built RAR4 volume header
does not reach junrar's continuation path. Multipart therefore stays PARTIAL in
`docs/feature-coverage.md` rather than being claimed on the strength of an untested path.
