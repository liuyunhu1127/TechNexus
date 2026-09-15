ALTER TABLE tn_ai_suggestion
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER version,
  ADD COLUMN available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER attempt_count,
  ADD COLUMN lease_until DATETIME(6) NULL AFTER available_at,
  ADD COLUMN last_error VARCHAR(1000) NULL AFTER lease_until;

DROP INDEX ix_ai_suggestion_queue ON tn_ai_suggestion;
CREATE INDEX ix_ai_suggestion_queue ON tn_ai_suggestion (state, available_at, lease_until, created_at, public_id);

ALTER TABLE tn_ai_suggestion
  ADD CONSTRAINT ck_ai_suggestion_attempt CHECK (attempt_count >= 0),
  ADD CONSTRAINT ck_ai_suggestion_state CHECK (
    state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','ACCEPTED','EDITED','REJECTED')
  );
