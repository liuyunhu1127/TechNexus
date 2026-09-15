CREATE TABLE tn_rate_limit (
  bucket_hash BINARY(32) NOT NULL,
  attempt_count INT UNSIGNED NOT NULL,
  reset_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_rate_limit PRIMARY KEY (bucket_hash),
  CONSTRAINT ck_rate_limit_attempt CHECK (attempt_count >= 1),
  INDEX ix_rate_limit_expiry (reset_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
