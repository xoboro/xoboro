CREATE TABLE authentication_activity (
  sequence_id      INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id          TEXT,
  email            TEXT,
  api_key_id       TEXT,
  api_key_comment  TEXT,
  ip               TEXT,
  user_agent       TEXT,
  success          INTEGER NOT NULL,
  error            TEXT,
  date_time_ms     INTEGER NOT NULL,
  source           TEXT,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT authentication_activity_success_boolean CHECK (success IN (0, 1)),
  CONSTRAINT authentication_activity_date_non_negative CHECK (date_time_ms >= 0)
);

CREATE INDEX authentication_activity_user_idx
  ON authentication_activity (user_id);

CREATE INDEX authentication_activity_date_idx
  ON authentication_activity (date_time_ms DESC, sequence_id DESC);
