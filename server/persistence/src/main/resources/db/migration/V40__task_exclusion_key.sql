ALTER TABLE task ADD COLUMN exclusion_key TEXT
  CONSTRAINT task_exclusion_not_blank
  CHECK (exclusion_key IS NULL OR length(trim(exclusion_key)) > 0);

CREATE INDEX task_active_exclusion_idx
  ON task (exclusion_key, state, lease_expires_at_ms);
