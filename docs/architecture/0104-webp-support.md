# 0104 — WebP: read yes, write no

## Status

Accepted.

## Context

The artwork coverage row had said WebP output was "blocked on a dependency
decision" since the tiled-composite work. The JDK ships no WebP `ImageIO` writer,
so a decision was owed.

Re-verified on this toolchain (Temurin 26.0.1) rather than taken from the earlier
note:

```
writers: BMP, GIF, JPEG, JPG, PNG, TIF, TIFF, WBMP
readers: BMP, GIF, JPEG, JPG, PNG, TIF, TIFF, WBMP
```

**The JDK has no WebP reader either.** That turned out to matter more than the
writer, because the question as framed was the less important half.

## The bug the framing hid

`LocalSourceArtworkAccess.EXTENSIONS` includes `webp`, so a scan offers to
discover `cover.webp` beside a series. `SafeJpegArtworkProcessor` then asks
`ImageIO` for a reader, finds none, and fails:

```
Uploaded artwork is not a supported image
```

So a perfectly ordinary WebP cover produced a **permanently failing artwork task**
— and a message about an upload, for a file nobody uploaded. Nothing in the code
ever claimed to *write* WebP (`PageImageFormat` is `JPEG` and `PNG` only), so the
open question was about the direction that was working fine, while the direction
that was broken was not tracked at all.

## Decision

### Reading WebP is supported

`com.twelvemonkeys.imageio:imageio-webp` is added as a **`runtimeOnly`**
dependency of `server:media`. It registers a reader through the `ImageIO` service
loader, so no code changes: `SafeJpegArtworkProcessor` already picks a reader by
content and converts whatever it decodes to JPEG.

It is pure Java with no native library, which is the property that made it
acceptable. `runtimeOnly` because nothing compiles against it — treating it as an
API dependency would invite a direct import that ties the module to this
particular plugin.

Verified on the real runtime classpath, not just in tests: it resolves into
`:server:app`'s `runtimeClasspath`, so a deployed server has the reader.

### Writing WebP is declined

The libraries that write WebP bind to a native `libwebp` (for example
`org.sejda.imageio:webp-imageio`). That makes the artifact architecture-specific,
and the container image is multi-architecture. Paying that — a native library, per
architecture, in the release path — to make a thumbnail somewhat smaller than the
JPEG that already works is not a trade worth making.

`imageio-webp` ships no writer, so the decision holds by construction rather than
by discipline. **A test asserts the absence**, so a dependency bump that started
shipping one would fail rather than quietly enabling output no design decision
asked for.

## Consequences

- A WebP cover in a library now becomes artwork instead of a dead task.
- Artwork output stays JPEG. The web UI requests server-chosen formats and offers
  no format toggle, so nothing user-facing changes.
- The coverage row no longer says "blocked on a dependency decision", because it
  is decided.
- The end-to-end decode test uses a **hand-built** 1×1 lossless VP8L fixture: one
  opaque pixel, single-symbol Huffman codes, no transforms. Nothing in the
  toolchain can write WebP — which is the point of this ADR — so generating one was
  not an option, and asserting only that a reader is *registered* would not have
  shown that decoding works.
  - The fixture carries trailing zero bytes. The decoder's bit reader refills in
    64-bit chunks and reads past the last meaningful bit, so a minimal payload
    fails with `EOFException` even though it is otherwise valid.
- The audit worth repeating elsewhere: a list of accepted file extensions is a
  promise, and it was not checked against what the decoder can actually read.
