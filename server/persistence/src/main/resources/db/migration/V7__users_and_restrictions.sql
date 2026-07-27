CREATE TABLE user_account (
  id                    TEXT    NOT NULL PRIMARY KEY,
  email                 TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  password_hash         TEXT    NOT NULL,
  shares_all_libraries  INTEGER NOT NULL DEFAULT 1,
  age_restriction       INTEGER,
  age_restriction_mode  TEXT,
  created_at_ms         INTEGER NOT NULL,
  updated_at_ms         INTEGER NOT NULL,
  CONSTRAINT user_email_not_blank CHECK (length(trim(email)) > 0),
  CONSTRAINT user_password_hash_not_blank CHECK (length(trim(password_hash)) > 0),
  CONSTRAINT user_shares_all_boolean CHECK (shares_all_libraries IN (0, 1)),
  CONSTRAINT user_age_non_negative CHECK (age_restriction IS NULL OR age_restriction >= 0),
  CONSTRAINT user_age_mode_valid CHECK (
    (age_restriction IS NULL AND age_restriction_mode IS NULL)
    OR
    (age_restriction IS NOT NULL AND age_restriction_mode IN ('ALLOW_ONLY', 'EXCLUDE'))
  )
);

CREATE TABLE user_role (
  user_id TEXT NOT NULL,
  role    TEXT NOT NULL,
  PRIMARY KEY (user_id, role),
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT user_role_valid CHECK (
    role IN ('ADMIN', 'FILE_DOWNLOAD', 'PAGE_STREAMING', 'KOBO_SYNC', 'KOREADER_SYNC')
  )
);

CREATE TABLE user_library_sharing (
  user_id    TEXT NOT NULL,
  library_id TEXT NOT NULL,
  PRIMARY KEY (user_id, library_id),
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  FOREIGN KEY (library_id) REFERENCES library(id) ON DELETE CASCADE
);

CREATE INDEX user_library_sharing_library_idx
  ON user_library_sharing (library_id, user_id);

CREATE TABLE user_sharing_label (
  user_id TEXT    NOT NULL,
  label   TEXT    NOT NULL,
  allow   INTEGER NOT NULL,
  PRIMARY KEY (user_id, label),
  FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
  CONSTRAINT user_sharing_label_not_blank CHECK (length(trim(label)) > 0),
  CONSTRAINT user_sharing_label_allow_boolean CHECK (allow IN (0, 1))
);
