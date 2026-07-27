CREATE TABLE artwork_thumbnail (
  id TEXT PRIMARY KEY NOT NULL,
  owner_kind TEXT NOT NULL
    CHECK (owner_kind IN ('MEDIA_ITEM', 'SERIES', 'COLLECTION', 'READ_LIST')),
  owner_id TEXT NOT NULL,
  artwork_type TEXT NOT NULL
    CHECK (artwork_type IN ('GENERATED', 'SIDECAR', 'USER_UPLOADED')),
  selected INTEGER NOT NULL DEFAULT 0 CHECK (selected IN (0, 1)),
  media_type TEXT NOT NULL,
  file_size INTEGER NOT NULL CHECK (file_size > 0),
  width INTEGER NOT NULL CHECK (width > 0),
  height INTEGER NOT NULL CHECK (height > 0),
  content BLOB NOT NULL,
  created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
  updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= created_at_ms),
  UNIQUE (owner_kind, owner_id, id)
) STRICT;

CREATE INDEX artwork_thumbnail_owner_idx
  ON artwork_thumbnail (owner_kind, owner_id, selected DESC, created_at_ms, id);

CREATE UNIQUE INDEX artwork_thumbnail_selected_idx
  ON artwork_thumbnail (owner_kind, owner_id)
  WHERE selected = 1;

CREATE TRIGGER artwork_thumbnail_delete_media_item
AFTER DELETE ON book
BEGIN
  DELETE FROM artwork_thumbnail
  WHERE owner_kind = 'MEDIA_ITEM' AND owner_id = OLD.id;
END;

CREATE TRIGGER artwork_thumbnail_delete_series
AFTER DELETE ON series
BEGIN
  DELETE FROM artwork_thumbnail
  WHERE owner_kind = 'SERIES' AND owner_id = OLD.id;
END;

CREATE TRIGGER artwork_thumbnail_delete_collection
AFTER DELETE ON series_collection
BEGIN
  DELETE FROM artwork_thumbnail
  WHERE owner_kind = 'COLLECTION' AND owner_id = OLD.id;
END;

CREATE TRIGGER artwork_thumbnail_delete_read_list
AFTER DELETE ON read_list
BEGIN
  DELETE FROM artwork_thumbnail
  WHERE owner_kind = 'READ_LIST' AND owner_id = OLD.id;
END;
