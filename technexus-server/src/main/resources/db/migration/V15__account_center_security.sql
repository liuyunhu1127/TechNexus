ALTER TABLE tn_user
  ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN avatar_object_key VARCHAR(255) NULL;

CREATE TABLE tn_account_email_action (
  user_id BIGINT UNSIGNED NOT NULL,
  action_type VARCHAR(24) NOT NULL,
  public_id BINARY(16) NOT NULL,
  current_email VARCHAR(254) NOT NULL,
  target_email VARCHAR(254) NOT NULL,
  session_version BIGINT NOT NULL,
  first_digest BINARY(32) NOT NULL,
  second_digest BINARY(32) NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, action_type),
  CONSTRAINT uk_account_email_action_public UNIQUE (public_id),
  CONSTRAINT fk_account_email_action_user FOREIGN KEY (user_id) REFERENCES tn_user (id),
  INDEX ix_account_email_action_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
