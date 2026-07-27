CREATE TABLE client_setting_global (
  setting_key        TEXT    NOT NULL PRIMARY KEY,
  setting_value      TEXT    NOT NULL,
  allow_unauthorized INTEGER NOT NULL,
  CONSTRAINT client_setting_global_key_not_blank CHECK (length(trim(setting_key)) > 0),
  CONSTRAINT client_setting_global_value_not_blank CHECK (length(trim(setting_value)) > 0),
  CONSTRAINT client_setting_global_allow_boolean CHECK (allow_unauthorized IN (0, 1))
);

CREATE TABLE client_setting_user (
  user_id       TEXT NOT NULL,
  setting_key   TEXT NOT NULL,
  setting_value TEXT NOT NULL,
  PRIMARY KEY (user_id, setting_key),
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT client_setting_user_key_not_blank CHECK (length(trim(setting_key)) > 0),
  CONSTRAINT client_setting_user_value_not_blank CHECK (length(trim(setting_value)) > 0)
);
