CREATE TABLE tn_batch_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  operation VARCHAR(48) NOT NULL,
  status VARCHAR(24) NOT NULL,
  total_count INT UNSIGNED NOT NULL,
  success_count INT UNSIGNED NOT NULL DEFAULT 0,
  failure_count INT UNSIGNED NOT NULL DEFAULT 0,
  requested_by BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_batch_job PRIMARY KEY (id),
  CONSTRAINT uk_batch_job_public_id UNIQUE (public_id),
  CONSTRAINT fk_batch_job_requested_by FOREIGN KEY (requested_by) REFERENCES tn_user (id),
  INDEX ix_batch_job_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_batch_job_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_job_id BIGINT UNSIGNED NOT NULL,
  sequence_no INT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  requested_version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(96) NULL,
  result_version BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_batch_job_item PRIMARY KEY (id),
  CONSTRAINT uk_batch_job_item_sequence UNIQUE (batch_job_id, sequence_no),
  CONSTRAINT fk_batch_job_item_job FOREIGN KEY (batch_job_id) REFERENCES tn_batch_job (id),
  INDEX ix_batch_job_item_target (target_type, target_public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
