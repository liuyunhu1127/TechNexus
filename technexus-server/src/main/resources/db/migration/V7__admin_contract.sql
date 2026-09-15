ALTER TABLE tn_price_object
  ADD COLUMN valid_from DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER currency;

ALTER TABLE tn_price_history
  MODIFY COLUMN reason VARCHAR(1000) NOT NULL;

ALTER TABLE tn_ai_suggestion
  ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'SUMMARY' AFTER public_id,
  ADD COLUMN target_version_id BINARY(16) NULL AFTER target_public_id,
  ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'QUEUED' AFTER suggestion,
  ADD COLUMN output_json JSON NULL AFTER state,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER decided_at;

UPDATE tn_ai_suggestion SET target_version_id=target_public_id WHERE target_version_id IS NULL;

ALTER TABLE tn_ai_suggestion
  MODIFY COLUMN target_version_id BINARY(16) NOT NULL;

CREATE INDEX ix_ai_suggestion_queue ON tn_ai_suggestion (state, created_at, public_id);
