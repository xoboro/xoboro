CREATE TABLE historical_event (
  id             TEXT    NOT NULL PRIMARY KEY,
  type           TEXT    NOT NULL,
  timestamp_ms   INTEGER NOT NULL,
  book_id        TEXT,
  series_id      TEXT,
  CHECK (length(trim(type)) > 0),
  CHECK (timestamp_ms >= 0)
);

CREATE INDEX historical_event_timestamp_idx
  ON historical_event(timestamp_ms DESC, id DESC);

CREATE INDEX historical_event_type_idx
  ON historical_event(type, timestamp_ms DESC);

CREATE TABLE historical_event_property (
  event_id  TEXT NOT NULL,
  key       TEXT NOT NULL,
  value     TEXT NOT NULL,
  PRIMARY KEY (event_id, key),
  FOREIGN KEY (event_id) REFERENCES historical_event(id) ON DELETE CASCADE,
  CHECK (length(trim(key)) > 0)
);

CREATE TABLE sync_point (
  id             TEXT    NOT NULL PRIMARY KEY,
  user_id        TEXT    NOT NULL,
  api_key_id     TEXT,
  created_at_ms  INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  FOREIGN KEY (api_key_id) REFERENCES user_api_key(id) ON DELETE CASCADE,
  CHECK (created_at_ms >= 0)
);

CREATE INDEX sync_point_user_api_key_idx
  ON sync_point(user_id, api_key_id, created_at_ms DESC);
