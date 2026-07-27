CREATE TABLE book_metadata (
  book_id                   TEXT    NOT NULL PRIMARY KEY,
  title                     TEXT    NOT NULL,
  summary                   TEXT    NOT NULL DEFAULT '',
  number                    TEXT    NOT NULL,
  number_sort               REAL    NOT NULL,
  release_date              TEXT,
  isbn                      TEXT    NOT NULL DEFAULT '',
  title_lock                INTEGER NOT NULL DEFAULT 0,
  summary_lock              INTEGER NOT NULL DEFAULT 0,
  number_lock               INTEGER NOT NULL DEFAULT 0,
  number_sort_lock          INTEGER NOT NULL DEFAULT 0,
  release_date_lock         INTEGER NOT NULL DEFAULT 0,
  authors_lock              INTEGER NOT NULL DEFAULT 0,
  tags_lock                 INTEGER NOT NULL DEFAULT 0,
  isbn_lock                 INTEGER NOT NULL DEFAULT 0,
  links_lock                INTEGER NOT NULL DEFAULT 0,
  created_at_ms             INTEGER NOT NULL,
  updated_at_ms             INTEGER NOT NULL,
  FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE,
  CHECK (release_date IS NULL OR release_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE TABLE book_metadata_author (
  book_id  TEXT    NOT NULL,
  ordinal  INTEGER NOT NULL,
  name     TEXT    NOT NULL,
  role     TEXT    NOT NULL,
  PRIMARY KEY (book_id, ordinal),
  FOREIGN KEY (book_id) REFERENCES book_metadata(book_id) ON DELETE CASCADE
);

CREATE TABLE book_metadata_tag (
  book_id  TEXT NOT NULL,
  tag      TEXT NOT NULL,
  PRIMARY KEY (book_id, tag),
  FOREIGN KEY (book_id) REFERENCES book_metadata(book_id) ON DELETE CASCADE
);

CREATE TABLE book_metadata_link (
  book_id  TEXT    NOT NULL,
  ordinal  INTEGER NOT NULL,
  label    TEXT    NOT NULL,
  url      TEXT    NOT NULL,
  PRIMARY KEY (book_id, ordinal),
  FOREIGN KEY (book_id) REFERENCES book_metadata(book_id) ON DELETE CASCADE
);

CREATE TABLE series_metadata (
  series_id                    TEXT    NOT NULL PRIMARY KEY,
  status                       TEXT    NOT NULL DEFAULT 'ONGOING',
  title                        TEXT    NOT NULL,
  title_sort                   TEXT    NOT NULL,
  summary                      TEXT    NOT NULL DEFAULT '',
  reading_direction            TEXT,
  publisher                    TEXT    NOT NULL DEFAULT '',
  age_rating                   INTEGER,
  language                     TEXT    NOT NULL DEFAULT '',
  total_book_count             INTEGER,
  status_lock                  INTEGER NOT NULL DEFAULT 0,
  title_lock                   INTEGER NOT NULL DEFAULT 0,
  title_sort_lock              INTEGER NOT NULL DEFAULT 0,
  summary_lock                 INTEGER NOT NULL DEFAULT 0,
  reading_direction_lock       INTEGER NOT NULL DEFAULT 0,
  publisher_lock               INTEGER NOT NULL DEFAULT 0,
  age_rating_lock              INTEGER NOT NULL DEFAULT 0,
  language_lock                INTEGER NOT NULL DEFAULT 0,
  genres_lock                  INTEGER NOT NULL DEFAULT 0,
  tags_lock                    INTEGER NOT NULL DEFAULT 0,
  total_book_count_lock        INTEGER NOT NULL DEFAULT 0,
  sharing_labels_lock          INTEGER NOT NULL DEFAULT 0,
  links_lock                   INTEGER NOT NULL DEFAULT 0,
  alternate_titles_lock        INTEGER NOT NULL DEFAULT 0,
  created_at_ms                INTEGER NOT NULL,
  updated_at_ms                INTEGER NOT NULL,
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE,
  CHECK (status IN ('ENDED', 'ONGOING', 'ABANDONED', 'HIATUS')),
  CHECK (reading_direction IS NULL OR reading_direction IN (
    'LEFT_TO_RIGHT', 'RIGHT_TO_LEFT', 'VERTICAL', 'WEBTOON'
  )),
  CHECK (age_rating IS NULL OR age_rating >= 0),
  CHECK (total_book_count IS NULL OR total_book_count >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE INDEX series_metadata_title_sort_idx ON series_metadata(title_sort);

CREATE TABLE series_metadata_genre (
  series_id  TEXT NOT NULL,
  genre      TEXT NOT NULL,
  PRIMARY KEY (series_id, genre),
  FOREIGN KEY (series_id) REFERENCES series_metadata(series_id) ON DELETE CASCADE
);

CREATE TABLE series_metadata_tag (
  series_id  TEXT NOT NULL,
  tag        TEXT NOT NULL,
  PRIMARY KEY (series_id, tag),
  FOREIGN KEY (series_id) REFERENCES series_metadata(series_id) ON DELETE CASCADE
);

CREATE TABLE series_metadata_sharing_label (
  series_id      TEXT NOT NULL,
  sharing_label  TEXT NOT NULL,
  PRIMARY KEY (series_id, sharing_label),
  FOREIGN KEY (series_id) REFERENCES series_metadata(series_id) ON DELETE CASCADE
);

CREATE TABLE series_metadata_link (
  series_id  TEXT    NOT NULL,
  ordinal    INTEGER NOT NULL,
  label      TEXT    NOT NULL,
  url        TEXT    NOT NULL,
  PRIMARY KEY (series_id, ordinal),
  FOREIGN KEY (series_id) REFERENCES series_metadata(series_id) ON DELETE CASCADE
);

CREATE TABLE series_metadata_alternate_title (
  series_id  TEXT    NOT NULL,
  ordinal    INTEGER NOT NULL,
  label      TEXT    NOT NULL,
  title      TEXT    NOT NULL,
  PRIMARY KEY (series_id, ordinal),
  FOREIGN KEY (series_id) REFERENCES series_metadata(series_id) ON DELETE CASCADE
);

CREATE TRIGGER initialize_book_metadata
AFTER INSERT ON book
BEGIN
  INSERT INTO book_metadata (
    book_id, title, number, number_sort, created_at_ms, updated_at_ms
  ) VALUES (
    NEW.id, NEW.name, CAST(NEW.number AS TEXT), CAST(NEW.number AS REAL),
    NEW.created_at_ms, NEW.updated_at_ms
  );
END;

CREATE TRIGGER initialize_series_metadata
AFTER INSERT ON series
BEGIN
  INSERT INTO series_metadata (
    series_id, title, title_sort, created_at_ms, updated_at_ms
  ) VALUES (
    NEW.id, NEW.name, NEW.name, NEW.created_at_ms, NEW.updated_at_ms
  );
END;

INSERT INTO book_metadata (
  book_id, title, number, number_sort, created_at_ms, updated_at_ms
)
SELECT id, name, CAST(number AS TEXT), CAST(number AS REAL), created_at_ms, updated_at_ms
FROM book;

INSERT INTO series_metadata (
  series_id, title, title_sort, created_at_ms, updated_at_ms
)
SELECT id, name, name, created_at_ms, updated_at_ms
FROM series;
