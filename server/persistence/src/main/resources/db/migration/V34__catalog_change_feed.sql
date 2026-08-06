-- A durable log of catalogue mutations, so a client that was away can be told what changed rather
-- than having to re-read everything.
--
-- ADR 0056 settled that SSE is "an invalidation channel rather than a durable log; reconnect recovery
-- must refresh current state". That is correct for a browser tab, and it does not scale to a device
-- holding a local copy: refreshing current state means re-reading 145,105 items to learn that three
-- of them moved. What it cannot do at all is report a deletion - an item that is gone is absent from
-- current state, which is indistinguishable from an item the client never saw.
--
-- The same ADR left the hook this needs: scan reconciliation already "captures events in the
-- transaction, but publishes them only after the transaction commits". Rows here are written in that
-- transaction, so a crash between commit and publish loses an SSE notification and not a change. A
-- feed assembled in the publish path instead would silently miss exactly the events a client cannot
-- recover by other means.
--
-- The cursor is `sequence`, not a timestamp. A timestamp needs a tiebreak for events in the same
-- millisecond, depends on the clock being monotonic across restarts, and orders two writers by when
-- they happened to read the clock rather than by when they committed. An integer that only goes up has
-- none of those problems.
--
-- AUTOINCREMENT is load-bearing rather than stylistic: a plain INTEGER PRIMARY KEY reuses rowids freed
-- by a delete, so after retention swept the tail a new row could take a number a client had already
-- passed - and that client would never see it. AUTOINCREMENT keeps the counter monotonic across
-- deletes, which is the whole contract of a cursor.
CREATE TABLE catalog_change (
  sequence       INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_kind    TEXT    NOT NULL CHECK (entity_kind IN ('MEDIA_ITEM', 'SERIES')),
  entity_id      TEXT    NOT NULL,
  mutation       TEXT    NOT NULL CHECK (mutation IN ('ADDED', 'UPDATED', 'DELETED')),
  -- Carried on the row rather than looked up when the feed is read. A deletion's library cannot be
  -- resolved afterwards from the entity, because the entity is what went away - and the library is
  -- what decides whether this reader may be told about it at all.
  library_id     TEXT    NOT NULL,
  occurred_at_ms INTEGER NOT NULL CHECK (occurred_at_ms >= 0),
  CONSTRAINT catalog_change_entity_not_blank CHECK (length(trim(entity_id)) > 0),
  CONSTRAINT catalog_change_library_not_blank CHECK (length(trim(library_id)) > 0)
) STRICT;

-- The read is always "this reader's libraries, after this cursor", so the index leads with the
-- library and ends with the cursor it scans forward along.
CREATE INDEX catalog_change_library_idx
  ON catalog_change (library_id, sequence);

-- How far retention has swept, so a cursor that fell behind can be told rather than quietly answered.
--
-- Without this, an empty answer has two meanings that a client cannot tell apart: nothing changed
-- since its cursor, or everything that changed was deleted before it asked. The second one loses
-- deletions permanently - the client keeps rows for items that no longer exist and has no way to
-- discover it. One number turns that into a resync it can act on.
--
-- Written in the same transaction as the delete it describes. Sweeping without recording the
-- watermark is precisely the silent gap this exists to close.
CREATE TABLE catalog_change_floor (
  id                     INTEGER PRIMARY KEY CHECK (id = 1),
  swept_through_sequence INTEGER NOT NULL DEFAULT 0 CHECK (swept_through_sequence >= 0)
) STRICT;

INSERT INTO catalog_change_floor (id, swept_through_sequence) VALUES (1, 0);
