CREATE TABLE user_announcement_read (
  user_id         TEXT NOT NULL,
  announcement_id TEXT NOT NULL,
  PRIMARY KEY (user_id, announcement_id),
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE
);
