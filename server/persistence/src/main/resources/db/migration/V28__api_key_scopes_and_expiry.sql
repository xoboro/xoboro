-- Scoped and expiring API keys. Both columns are nullable/defaulted so existing rows keep their
-- current meaning: no scope row means "whatever the owner currently holds", and a null expiry means
-- the key never expires. Scopes live in a child table rather than in a delimited column so a scope
-- value is a row that can be indexed and constrained, not a substring.
ALTER TABLE user_api_key ADD COLUMN expires_at_ms INTEGER;

CREATE TABLE user_api_key_scope (
  api_key_id TEXT NOT NULL,
  scope      TEXT NOT NULL,
  PRIMARY KEY (api_key_id, scope),
  FOREIGN KEY (api_key_id) REFERENCES user_api_key(id) ON DELETE CASCADE,
  CONSTRAINT user_api_key_scope_not_blank CHECK (length(trim(scope)) > 0)
);
