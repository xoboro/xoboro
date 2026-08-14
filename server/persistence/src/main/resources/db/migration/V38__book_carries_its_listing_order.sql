-- The default media-item order spans three tables, so no index could serve it.
--
-- `sm.title_sort, bm.number_sort, b.relative_uri, b.id` reads from `series_metadata`,
-- `book_metadata` and `book`, and an index lives on one table. Against the live catalog the first
-- page of that order planned as `SCAN b` + `USE TEMP B-TREE FOR ORDER BY` and cost 143 ms: 145,105
-- rows sorted to return 24. This brings the two foreign columns onto `book`, where the other two
-- already are, so the whole key fits one index.
--
-- Both are copies of a value that already exists, which is only safe because the database keeps
-- them rather than a caller: the triggers below carry every write that can change either one.
--
-- Updating them wakes nothing. Every existing trigger on `book` is scoped to specific columns -
-- `name`, `series_id`, `relative_uri`, `deleted_at_ms`, `created_at_ms`, `updated_at_ms` - and
-- neither new column is among them, so maintaining this order costs no search-index rewrite and no
-- aggregation dirty mark. That mattered enough to check: V35 exists because triggers on this path
-- had made a cold scan quadratic.

ALTER TABLE book ADD COLUMN series_title_sort TEXT NOT NULL DEFAULT '';
ALTER TABLE book ADD COLUMN number_sort REAL NOT NULL DEFAULT 0;

UPDATE book
SET series_title_sort = COALESCE(
  (SELECT title_sort FROM series_metadata WHERE series_id = book.series_id),
  ''
),
number_sort = COALESCE(
  (SELECT number_sort FROM book_metadata WHERE book_id = book.id),
  0
);

-- A book's series exists before the book does - the foreign key says so, and `initialize_series_
-- metadata` fills the metadata row with the series - so the title sort is readable here.
CREATE TRIGGER book_series_title_sort_on_insert
AFTER INSERT ON book
BEGIN
  UPDATE book
  SET series_title_sort = COALESCE(
    (SELECT title_sort FROM series_metadata WHERE series_id = NEW.series_id),
    ''
  )
  WHERE id = NEW.id;
END;

CREATE TRIGGER book_series_title_sort_on_move
AFTER UPDATE OF series_id ON book
BEGIN
  UPDATE book
  SET series_title_sort = COALESCE(
    (SELECT title_sort FROM series_metadata WHERE series_id = NEW.series_id),
    ''
  )
  WHERE id = NEW.id;
END;

CREATE TRIGGER book_series_title_sort_on_rename
AFTER UPDATE OF title_sort ON series_metadata
BEGIN
  UPDATE book
  SET series_title_sort = NEW.title_sort
  WHERE series_id = NEW.series_id;
END;

-- Read from `book_metadata` rather than from `book.number`, because that is where the sort value
-- is decided: `initialize_book_metadata` derives it from the book, and the metadata repository
-- overwrites it with whatever the file's own metadata says.
CREATE TRIGGER book_number_sort_on_metadata_insert
AFTER INSERT ON book_metadata
BEGIN
  UPDATE book SET number_sort = NEW.number_sort WHERE id = NEW.book_id;
END;

CREATE TRIGGER book_number_sort_on_metadata_update
AFTER UPDATE OF number_sort ON book_metadata
BEGIN
  UPDATE book SET number_sort = NEW.number_sort WHERE id = NEW.book_id;
END;

-- The default listing order, end to end. `COLLATE NOCASE` is repeated from the ORDER BY: an index
-- only serves a sort whose collation it shares.
CREATE INDEX book_series_listing_idx
  ON book (series_title_sort COLLATE NOCASE, number_sort, relative_uri, id);
