CREATE TABLE catalog_scan_session (
  id                  TEXT    NOT NULL PRIMARY KEY,
  library_id          TEXT    NOT NULL,
  deep                INTEGER NOT NULL,
  status              TEXT    NOT NULL,
  started_at_ms       INTEGER NOT NULL,
  completed_at_ms     INTEGER,
  failed_entries      INTEGER NOT NULL DEFAULT 0,
  ignored_files       INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT catalog_scan_session_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT catalog_scan_session_deep_valid CHECK (deep IN (0, 1)),
  CONSTRAINT catalog_scan_session_status_valid
    CHECK (status IN ('STAGING', 'COMPLETED', 'ABORTED')),
  CONSTRAINT catalog_scan_session_failed_non_negative CHECK (failed_entries >= 0),
  CONSTRAINT catalog_scan_session_ignored_non_negative CHECK (ignored_files >= 0)
);

CREATE TABLE catalog_scan_candidate (
  session_id            TEXT    NOT NULL,
  relative_path         TEXT    NOT NULL,
  source_item_id        TEXT    NOT NULL,
  source_identity       TEXT,
  name                  TEXT    NOT NULL,
  media_kind            TEXT    NOT NULL,
  file_size             INTEGER NOT NULL,
  file_modified_ms      INTEGER NOT NULL,
  series_relative_path  TEXT    NOT NULL,
  series_source_item_id TEXT    NOT NULL,
  series_name           TEXT    NOT NULL,
  oneshot               INTEGER NOT NULL,
  matched_book_id       TEXT,
  change_type           TEXT,
  was_deleted           INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (session_id, relative_path),
  CONSTRAINT catalog_scan_candidate_session_fk
    FOREIGN KEY (session_id) REFERENCES catalog_scan_session (id) ON DELETE CASCADE,
  CONSTRAINT catalog_scan_candidate_media_kind_valid
    CHECK (media_kind IN ('COMIC_ARCHIVE', 'PDF', 'EPUB')),
  CONSTRAINT catalog_scan_candidate_size_non_negative CHECK (file_size >= 0),
  CONSTRAINT catalog_scan_candidate_oneshot_valid CHECK (oneshot IN (0, 1)),
  CONSTRAINT catalog_scan_candidate_was_deleted_valid CHECK (was_deleted IN (0, 1)),
  CONSTRAINT catalog_scan_candidate_change_type_valid
    CHECK (change_type IS NULL OR change_type IN ('UNCHANGED', 'CHANGED', 'MOVED', 'NEW'))
);

CREATE INDEX catalog_scan_candidate_identity_idx
  ON catalog_scan_candidate (session_id, source_identity)
  WHERE source_identity IS NOT NULL;
CREATE INDEX catalog_scan_candidate_series_idx
  ON catalog_scan_candidate (session_id, series_relative_path);
