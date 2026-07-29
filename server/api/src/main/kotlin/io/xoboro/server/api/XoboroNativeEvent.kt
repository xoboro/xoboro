package io.xoboro.server.api

/**
 * A native event, already named and scoped, awaiting per-subscriber filtering.
 *
 * [payload] carries identifiers only, never entity snapshots. A snapshot would embed the
 * visibility decision made when it was serialized, and that decision can be wrong by the time it
 * is delivered: grants change while a stream is open, and the resume buffer replays. A snapshot
 * that was authorized when written and unauthorized when read is a leak with no point at which
 * it can be detected. An identifier forces the client back through the read route, which
 * re-authorizes against current state and answers 404 when it must, leaving exactly one place
 * where the decision lives.
 *
 * [seq] is assigned by the hub when the event is published, before any per-subscriber filtering,
 * so every subscriber shares one numbering. Gaps in the sequence a given subscriber observes are
 * therefore normal and must never be read as loss.
 */
data class XoboroNativeEvent(
  val name: String,
  val scope: XoboroNativeEventScope,
  val payload: String,
) {
  init {
    require(name.isNotBlank()) { "Native event name must not be blank" }
    require(payload.isNotBlank()) { "Native event payload must not be blank" }
  }
}
