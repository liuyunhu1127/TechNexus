ALTER TABLE tn_file_object
  ADD COLUMN scan_attempt INT UNSIGNED NOT NULL DEFAULT 0 AFTER detected_mime,
  ADD COLUMN next_scan_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER scan_attempt,
  ADD COLUMN scan_lease_until DATETIME(6) NULL AFTER next_scan_at,
  ADD COLUMN scan_error VARCHAR(1000) NULL AFTER scan_lease_until;

CREATE INDEX ix_file_object_scan_queue
  ON tn_file_object (state, next_scan_at, scan_lease_until, id);
