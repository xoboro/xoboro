ALTER TABLE book
  ADD COLUMN media_item_type TEXT NOT NULL DEFAULT 'BOOK'
  CHECK (media_item_type IN ('COMIC', 'NOVEL', 'BOOK', 'VIDEO', 'AUDIO'));

UPDATE book
SET media_item_type =
  CASE media_kind
    WHEN 'COMIC_ARCHIVE' THEN 'COMIC'
    WHEN 'EPUB' THEN 'NOVEL'
    ELSE 'BOOK'
  END;

CREATE INDEX book_library_media_item_type_idx
  ON book (library_id, media_item_type, deleted_at_ms);
