ALTER TABLE tn_auth_credential
  ADD COLUMN locked_until DATETIME(6) NULL AFTER failed_attempt;
