CREATE TABLE task (
  id                  TEXT    NOT NULL PRIMARY KEY,
  task_type           TEXT    NOT NULL,
  payload_json        TEXT    NOT NULL,
  priority            INTEGER NOT NULL,
  group_id            TEXT,
  state               TEXT    NOT NULL,
  attempt_count       INTEGER NOT NULL DEFAULT 0,
  max_attempts        INTEGER NOT NULL,
  available_at_ms     INTEGER NOT NULL,
  lease_owner         TEXT,
  lease_token         TEXT,
  lease_expires_at_ms INTEGER,
  last_error          TEXT,
  created_at_ms       INTEGER NOT NULL,
  updated_at_ms       INTEGER NOT NULL,
  CONSTRAINT task_type_not_blank CHECK (length(trim(task_type)) > 0),
  CONSTRAINT task_payload_not_blank CHECK (length(trim(payload_json)) > 0),
  CONSTRAINT task_priority_valid CHECK (priority BETWEEN 0 AND 8),
  CONSTRAINT task_group_not_blank CHECK (group_id IS NULL OR length(trim(group_id)) > 0),
  CONSTRAINT task_state_valid CHECK (state IN ('PENDING', 'RUNNING', 'DEAD')),
  CONSTRAINT task_attempt_count_non_negative CHECK (attempt_count >= 0),
  CONSTRAINT task_max_attempts_positive CHECK (max_attempts > 0),
  CONSTRAINT task_lease_consistent CHECK (
    (state = 'RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
      AND lease_expires_at_ms IS NOT NULL)
    OR
    (state <> 'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL
      AND lease_expires_at_ms IS NULL)
  )
);

CREATE INDEX task_available_idx
  ON task (state, available_at_ms, priority DESC, created_at_ms);
CREATE INDEX task_active_group_idx
  ON task (group_id, state, lease_expires_at_ms);
