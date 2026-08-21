CREATE INDEX task_pending_background_age_idx
  ON task (created_at_ms, id)
  WHERE state = 'PENDING' AND priority < 4;
