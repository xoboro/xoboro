CREATE TABLE server_setting (
  setting_key   TEXT NOT NULL PRIMARY KEY,
  setting_value TEXT NOT NULL,
  CONSTRAINT server_setting_key_not_blank CHECK (length(trim(setting_key)) > 0)
);
