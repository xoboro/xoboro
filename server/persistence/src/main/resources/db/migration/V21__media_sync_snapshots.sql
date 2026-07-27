ALTER TABLE sync_point
  ADD COLUMN cursor INTEGER NOT NULL DEFAULT 0
    CHECK (cursor >= 0);

CREATE TABLE media_sync_item (
  sync_point_id  TEXT    NOT NULL,
  media_item_id  TEXT    NOT NULL,
  revision       TEXT    NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  PRIMARY KEY (sync_point_id, media_item_id),
  FOREIGN KEY (sync_point_id) REFERENCES sync_point(id) ON DELETE CASCADE,
  CHECK (length(trim(revision)) > 0),
  CHECK (created_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE media_sync_read_list (
  sync_point_id  TEXT    NOT NULL,
  read_list_id   TEXT    NOT NULL,
  name           TEXT    NOT NULL,
  revision       TEXT    NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  PRIMARY KEY (sync_point_id, read_list_id),
  FOREIGN KEY (sync_point_id) REFERENCES sync_point(id) ON DELETE CASCADE,
  CHECK (length(trim(name)) > 0),
  CHECK (length(trim(revision)) > 0),
  CHECK (created_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE media_sync_read_list_item (
  sync_point_id  TEXT    NOT NULL,
  read_list_id   TEXT    NOT NULL,
  media_item_id  TEXT    NOT NULL,
  ordinal        INTEGER NOT NULL,
  PRIMARY KEY (sync_point_id, read_list_id, media_item_id),
  FOREIGN KEY (sync_point_id, read_list_id)
    REFERENCES media_sync_read_list(sync_point_id, read_list_id)
    ON DELETE CASCADE,
  CHECK (ordinal >= 0)
);

CREATE INDEX media_sync_read_list_item_order_idx
  ON media_sync_read_list_item(sync_point_id, read_list_id, ordinal);

CREATE TABLE media_sync_progress (
  sync_point_id  TEXT NOT NULL,
  media_item_id  TEXT NOT NULL,
  revision       TEXT NOT NULL,
  PRIMARY KEY (sync_point_id, media_item_id),
  FOREIGN KEY (sync_point_id) REFERENCES sync_point(id) ON DELETE CASCADE,
  CHECK (length(trim(revision)) > 0)
);
