CREATE TABLE media_item (
  id                TEXT    NOT NULL PRIMARY KEY,
  library_id        TEXT    NOT NULL,
  media_item_type   TEXT    NOT NULL,
  relative_uri      TEXT    NOT NULL,
  source_item_id    TEXT    NOT NULL,
  source_identity   TEXT,
  name              TEXT    NOT NULL,
  file_size         INTEGER NOT NULL,
  file_modified_ms  INTEGER NOT NULL,
  duration_ms       INTEGER,
  deleted_at_ms     INTEGER,
  created_at_ms     INTEGER NOT NULL,
  updated_at_ms     INTEGER NOT NULL,
  CONSTRAINT media_item_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT media_item_type_valid
    CHECK (media_item_type IN ('COMIC', 'NOVEL', 'BOOK', 'VIDEO', 'AUDIO')),
  CONSTRAINT media_item_relative_uri_not_blank
    CHECK (length(trim(relative_uri)) > 0),
  CONSTRAINT media_item_source_item_id_not_blank
    CHECK (length(trim(source_item_id)) > 0),
  CONSTRAINT media_item_source_identity_not_blank
    CHECK (source_identity IS NULL OR length(trim(source_identity)) > 0),
  CONSTRAINT media_item_name_not_blank CHECK (length(trim(name)) > 0),
  CONSTRAINT media_item_file_size_non_negative CHECK (file_size >= 0),
  CONSTRAINT media_item_file_modified_non_negative CHECK (file_modified_ms >= 0),
  CONSTRAINT media_item_duration_non_negative
    CHECK (duration_ms IS NULL OR duration_ms >= 0),
  CONSTRAINT media_item_deleted_non_negative
    CHECK (deleted_at_ms IS NULL OR deleted_at_ms >= 0),
  CONSTRAINT media_item_created_non_negative CHECK (created_at_ms >= 0),
  CONSTRAINT media_item_updated_after_created CHECK (updated_at_ms >= created_at_ms),
  CONSTRAINT media_item_location_unique UNIQUE (library_id, relative_uri)
);

CREATE INDEX media_item_library_type_deleted_idx
  ON media_item (library_id, media_item_type, deleted_at_ms);
CREATE INDEX media_item_library_source_identity_idx
  ON media_item (library_id, source_identity)
  WHERE source_identity IS NOT NULL;

INSERT INTO media_item (
  id, library_id, media_item_type, relative_uri, source_item_id,
  source_identity, name, file_size, file_modified_ms, duration_ms,
  deleted_at_ms, created_at_ms, updated_at_ms
)
SELECT
  id, library_id, media_item_type, relative_uri,
  coalesce(nullif(trim(source_item_id), ''), relative_uri),
  source_identity, name, file_size, file_modified_ms, NULL,
  deleted_at_ms, created_at_ms, updated_at_ms
FROM book;

CREATE TRIGGER media_item_from_book_insert
AFTER INSERT ON book
BEGIN
  INSERT INTO media_item (
    id, library_id, media_item_type, relative_uri, source_item_id,
    source_identity, name, file_size, file_modified_ms, duration_ms,
    deleted_at_ms, created_at_ms, updated_at_ms
  ) VALUES (
    NEW.id, NEW.library_id, NEW.media_item_type, NEW.relative_uri,
    coalesce(nullif(trim(NEW.source_item_id), ''), NEW.relative_uri),
    NEW.source_identity, NEW.name, NEW.file_size,
    NEW.file_modified_ms, NULL, NEW.deleted_at_ms, NEW.created_at_ms,
    NEW.updated_at_ms
  );
END;

CREATE TRIGGER media_item_from_book_update
AFTER UPDATE OF
  library_id, media_item_type, relative_uri, source_item_id, source_identity,
  name, file_size, file_modified_ms, deleted_at_ms, updated_at_ms
ON book
BEGIN
  UPDATE media_item SET
    library_id = NEW.library_id,
    media_item_type = NEW.media_item_type,
    relative_uri = NEW.relative_uri,
    source_item_id =
      coalesce(nullif(trim(NEW.source_item_id), ''), NEW.relative_uri),
    source_identity = NEW.source_identity,
    name = NEW.name,
    file_size = NEW.file_size,
    file_modified_ms = NEW.file_modified_ms,
    duration_ms = NULL,
    deleted_at_ms = NEW.deleted_at_ms,
    created_at_ms = NEW.created_at_ms,
    updated_at_ms = NEW.updated_at_ms
  WHERE id = NEW.id;

  SELECT CASE
    WHEN changes() <> 1
    THEN RAISE(ABORT, 'canonical media item is missing')
  END;
END;

CREATE TRIGGER media_item_from_book_delete
AFTER DELETE ON book
BEGIN
  DELETE FROM media_item WHERE id = OLD.id;
END;

CREATE TRIGGER media_item_document_insert_guard
BEFORE INSERT ON media_item
WHEN NEW.media_item_type IN ('COMIC', 'NOVEL', 'BOOK')
  AND NOT EXISTS (SELECT 1 FROM book WHERE id = NEW.id)
BEGIN
  SELECT RAISE(ABORT, 'document media items require a book adapter');
END;

CREATE TRIGGER media_item_document_update_guard
BEFORE UPDATE OF media_item_type ON media_item
WHEN NEW.media_item_type IN ('COMIC', 'NOVEL', 'BOOK')
  AND NOT EXISTS (SELECT 1 FROM book WHERE id = NEW.id)
BEGIN
  SELECT RAISE(ABORT, 'document media items require a book adapter');
END;
