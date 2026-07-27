CREATE TABLE read_progress (
  book_id         TEXT    NOT NULL,
  user_id         TEXT    NOT NULL,
  page            INTEGER NOT NULL,
  completed       INTEGER NOT NULL,
  read_at_ms      INTEGER NOT NULL,
  device_id       TEXT    NOT NULL DEFAULT '',
  device_name     TEXT    NOT NULL DEFAULT '',
  locator_json    TEXT,
  created_at_ms   INTEGER NOT NULL,
  updated_at_ms   INTEGER NOT NULL,
  PRIMARY KEY (book_id, user_id),
  FOREIGN KEY (book_id) REFERENCES book(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CHECK (page >= 0),
  CHECK (completed IN (0, 1)),
  CHECK (read_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE INDEX read_progress_user_read_date_idx
  ON read_progress(user_id, read_at_ms DESC);

CREATE TABLE read_progress_series (
  series_id                TEXT    NOT NULL,
  user_id                  TEXT    NOT NULL,
  books_read_count         INTEGER NOT NULL,
  books_in_progress_count  INTEGER NOT NULL,
  last_read_at_ms          INTEGER NOT NULL,
  created_at_ms            INTEGER NOT NULL,
  updated_at_ms            INTEGER NOT NULL,
  PRIMARY KEY (series_id, user_id),
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CHECK (books_read_count >= 0),
  CHECK (books_in_progress_count >= 0),
  CHECK (last_read_at_ms >= 0),
  CHECK (updated_at_ms >= created_at_ms)
);

CREATE INDEX read_progress_series_user_read_date_idx
  ON read_progress_series(user_id, last_read_at_ms DESC);
