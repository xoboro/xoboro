CREATE TABLE catalog_scan_checkpoint (
  library_id         TEXT    NOT NULL PRIMARY KEY,
  source_id          TEXT    NOT NULL,
  root_item_id       TEXT    NOT NULL,
  configuration_key TEXT    NOT NULL,
  fingerprint        TEXT    NOT NULL,
  completed_at_ms    INTEGER NOT NULL,
  CONSTRAINT catalog_scan_checkpoint_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT catalog_scan_checkpoint_source_not_blank CHECK (length(trim(source_id)) > 0),
  CONSTRAINT catalog_scan_checkpoint_root_not_blank CHECK (length(trim(root_item_id)) > 0),
  CONSTRAINT catalog_scan_checkpoint_configuration_not_blank
    CHECK (length(configuration_key) > 0),
  CONSTRAINT catalog_scan_checkpoint_fingerprint_not_blank CHECK (length(trim(fingerprint)) > 0),
  CONSTRAINT catalog_scan_checkpoint_completed_non_negative CHECK (completed_at_ms >= 0)
);
