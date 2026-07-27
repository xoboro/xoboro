ALTER TABLE library ADD COLUMN source_id TEXT NOT NULL DEFAULT 'local';
ALTER TABLE library ADD COLUMN import_comic_info_book INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_comic_info_series INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_comic_info_collection INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_comic_info_read_list INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_comic_info_series_append_volume INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_epub_book INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_epub_series INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_mylar_series INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_local_artwork INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN import_barcode_isbn INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN scan_force_modified_time INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN scan_on_startup INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN scan_interval TEXT NOT NULL DEFAULT 'EVERY_6H';
ALTER TABLE library ADD COLUMN scan_cbx INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN scan_pdf INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN scan_epub INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN repair_extensions INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN convert_to_cbz INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN empty_trash_after_scan INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN series_cover TEXT NOT NULL DEFAULT 'FIRST';
ALTER TABLE library ADD COLUMN hash_files INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN hash_pages INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN hash_koreader INTEGER NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN analyze_dimensions INTEGER NOT NULL DEFAULT 1;
ALTER TABLE library ADD COLUMN oneshots_directory TEXT;
ALTER TABLE library ADD COLUMN unavailable_at_ms INTEGER;

CREATE TABLE library_scan_exclusion (
  library_id        TEXT NOT NULL,
  exclusion         TEXT NOT NULL,
  CONSTRAINT library_scan_exclusion_library_fk
    FOREIGN KEY (library_id) REFERENCES library (id) ON DELETE CASCADE,
  CONSTRAINT library_scan_exclusion_not_blank CHECK (length(trim(exclusion)) > 0),
  CONSTRAINT library_scan_exclusion_unique UNIQUE (library_id, exclusion)
);

CREATE INDEX library_scan_exclusion_library_id_idx
  ON library_scan_exclusion (library_id);
