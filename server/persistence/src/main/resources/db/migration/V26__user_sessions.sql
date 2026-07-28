CREATE TABLE user_session (
  token_digest         TEXT    NOT NULL PRIMARY KEY,
  user_id              TEXT    NOT NULL,
  created_at_ms        INTEGER NOT NULL,
  last_accessed_at_ms  INTEGER NOT NULL,
  expires_at_ms        INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT user_session_digest_not_blank CHECK (length(trim(token_digest)) > 0),
  CONSTRAINT user_session_created_non_negative CHECK (created_at_ms >= 0),
  CONSTRAINT user_session_access_valid CHECK (last_accessed_at_ms >= created_at_ms),
  CONSTRAINT user_session_expiry_valid CHECK (expires_at_ms >= last_accessed_at_ms)
);

CREATE INDEX user_session_user_idx
  ON user_session (user_id, token_digest);

CREATE INDEX user_session_expiry_idx
  ON user_session (expires_at_ms);
