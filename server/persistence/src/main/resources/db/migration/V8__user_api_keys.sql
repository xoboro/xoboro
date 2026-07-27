CREATE TABLE user_api_key (
  id             TEXT NOT NULL PRIMARY KEY,
  user_id        TEXT NOT NULL,
  key_hash       TEXT NOT NULL UNIQUE,
  comment        TEXT NOT NULL COLLATE NOCASE,
  created_at_ms  INTEGER NOT NULL,
  updated_at_ms  INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT user_api_key_hash_not_blank CHECK (length(trim(key_hash)) > 0),
  CONSTRAINT user_api_key_comment_not_blank CHECK (length(trim(comment)) > 0),
  CONSTRAINT user_api_key_comment_unique UNIQUE (user_id, comment)
);

CREATE INDEX user_api_key_user_idx
  ON user_api_key (user_id, created_at_ms, id);
