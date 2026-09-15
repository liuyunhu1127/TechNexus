CREATE TABLE tn_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  email_normalized VARCHAR(254) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  session_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_user PRIMARY KEY (id),
  CONSTRAINT uk_user_public_id UNIQUE (public_id),
  CONSTRAINT uk_user_email UNIQUE (email_normalized),
  CONSTRAINT ck_user_status CHECK (status IN ('REGISTERED','ACTIVE','LOCKED','DISABLED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_role PRIMARY KEY (id),
  CONSTRAINT uk_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(96) NOT NULL,
  description VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_permission PRIMARY KEY (id),
  CONSTRAINT uk_permission_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_user_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_user_role PRIMARY KEY (id),
  CONSTRAINT uk_user_role_pair UNIQUE (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES tn_user (id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES tn_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_role_permission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  role_id BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_role_permission PRIMARY KEY (id),
  CONSTRAINT uk_role_permission_pair UNIQUE (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES tn_role (id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES tn_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_auth_credential (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  failed_attempt BIGINT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_auth_credential PRIMARY KEY (id),
  CONSTRAINT uk_auth_credential_user UNIQUE (user_id),
  CONSTRAINT fk_auth_credential_user FOREIGN KEY (user_id) REFERENCES tn_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_auth_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sid BINARY(16) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  token_family BINARY(16) NOT NULL,
  refresh_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_auth_session PRIMARY KEY (id),
  CONSTRAINT uk_auth_session_sid UNIQUE (sid),
  CONSTRAINT uk_auth_session_refresh UNIQUE (refresh_hash),
  CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES tn_user (id),
  INDEX ix_auth_session_user_status (user_id, status),
  INDEX ix_auth_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_article (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  state VARCHAR(24) NOT NULL,
  visibility VARCHAR(24) NOT NULL,
  published_version_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  published_at DATETIME(6) NULL,
  pinned_rank INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_article PRIMARY KEY (id),
  CONSTRAINT uk_article_public_id UNIQUE (public_id),
  CONSTRAINT fk_article_user FOREIGN KEY (owner_id) REFERENCES tn_user (id),
  INDEX ix_article_feed (state, visibility, pinned_rank, published_at, public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_article_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  article_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  title VARCHAR(120) NOT NULL,
  summary TEXT NULL,
  body LONGTEXT NOT NULL,
  state VARCHAR(20) NOT NULL,
  checksum BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_article_version PRIMARY KEY (id),
  CONSTRAINT uk_article_version_public_id UNIQUE (public_id),
  CONSTRAINT uk_article_version_revision UNIQUE (article_id, revision),
  CONSTRAINT fk_article_version_article FOREIGN KEY (article_id) REFERENCES tn_article (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tn_article ADD CONSTRAINT fk_article_published_version FOREIGN KEY (published_version_id) REFERENCES tn_article_version (id) ON DELETE SET NULL;

CREATE TABLE tn_post (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  post_type VARCHAR(16) NOT NULL,
  state VARCHAR(24) NOT NULL,
  visibility VARCHAR(24) NOT NULL,
  published_version_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  published_at DATETIME(6) NULL,
  pinned_rank INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_post PRIMARY KEY (id),
  CONSTRAINT uk_post_public_id UNIQUE (public_id),
  CONSTRAINT fk_post_user FOREIGN KEY (owner_id) REFERENCES tn_user (id),
  INDEX ix_post_feed (state, visibility, pinned_rank, published_at, public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_post_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  title VARCHAR(120) NOT NULL,
  summary TEXT NULL,
  body LONGTEXT NOT NULL,
  state VARCHAR(20) NOT NULL,
  checksum BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_post_version PRIMARY KEY (id),
  CONSTRAINT uk_post_version_public_id UNIQUE (public_id),
  CONSTRAINT uk_post_version_revision UNIQUE (post_id, revision),
  CONSTRAINT fk_post_version_post FOREIGN KEY (post_id) REFERENCES tn_post (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tn_post ADD CONSTRAINT fk_post_published_version FOREIGN KEY (published_version_id) REFERENCES tn_post_version (id) ON DELETE SET NULL;

CREATE TABLE tn_comment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  author_id BIGINT UNSIGNED NOT NULL,
  parent_id BIGINT UNSIGNED NULL,
  root_id BIGINT UNSIGNED NULL,
  target_type VARCHAR(16) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  body TEXT NOT NULL,
  display_state VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_comment PRIMARY KEY (id),
  CONSTRAINT uk_comment_public_id UNIQUE (public_id),
  CONSTRAINT fk_comment_user FOREIGN KEY (author_id) REFERENCES tn_user (id),
  CONSTRAINT fk_comment_parent FOREIGN KEY (parent_id) REFERENCES tn_comment (id),
  CONSTRAINT fk_comment_root FOREIGN KEY (root_id) REFERENCES tn_comment (id),
  INDEX ix_comment_target (target_type, target_public_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_tag (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  normalized_name VARCHAR(48) NOT NULL,
  merged_into_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_tag PRIMARY KEY (id),
  CONSTRAINT uk_tag_public_id UNIQUE (public_id),
  CONSTRAINT uk_tag_name UNIQUE (normalized_name),
  CONSTRAINT fk_tag_merged_into FOREIGN KEY (merged_into_id) REFERENCES tn_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_article_tag (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  article_id BIGINT UNSIGNED NOT NULL,
  tag_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_article_tag PRIMARY KEY (id),
  CONSTRAINT uk_article_tag_pair UNIQUE (article_id, tag_id),
  CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES tn_article (id),
  CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES tn_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_post_tag (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  post_id BIGINT UNSIGNED NOT NULL,
  tag_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_post_tag PRIMARY KEY (id),
  CONSTRAINT uk_post_tag_pair UNIQUE (post_id, tag_id),
  CONSTRAINT fk_post_tag_post FOREIGN KEY (post_id) REFERENCES tn_post (id),
  CONSTRAINT fk_post_tag_tag FOREIGN KEY (tag_id) REFERENCES tn_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_demand (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  state VARCHAR(24) NOT NULL,
  title VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  requirements JSON NULL,
  budget_min DECIMAL(12,2) NULL,
  budget_max DECIMAL(12,2) NULL,
  platform_quote DECIMAL(12,2) NULL,
  final_quote DECIMAL(12,2) NULL,
  deadline_at DATETIME(6) NULL,
  contact_ciphertext VARBINARY(2048) NULL,
  visibility VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_demand PRIMARY KEY (id),
  CONSTRAINT uk_demand_public_id UNIQUE (public_id),
  CONSTRAINT fk_demand_user FOREIGN KEY (owner_id) REFERENCES tn_user (id),
  CONSTRAINT ck_demand_budget CHECK ((budget_min IS NULL OR budget_min >= 0) AND (budget_max IS NULL OR budget_max >= 0) AND (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max)),
  INDEX ix_demand_public (state, visibility, deadline_at, public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_demand_proposal (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  demand_id BIGINT UNSIGNED NOT NULL,
  provider_id BIGINT UNSIGNED NOT NULL,
  plan TEXT NOT NULL,
  tech_stack JSON NULL,
  quote DECIMAL(12,2) NOT NULL,
  state VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_demand_proposal PRIMARY KEY (id),
  CONSTRAINT uk_demand_proposal_public_id UNIQUE (public_id),
  CONSTRAINT fk_demand_proposal_demand FOREIGN KEY (demand_id) REFERENCES tn_demand (id),
  CONSTRAINT fk_demand_proposal_user FOREIGN KEY (provider_id) REFERENCES tn_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_demand_state_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  demand_id BIGINT UNSIGNED NOT NULL,
  from_state VARCHAR(24) NULL,
  to_state VARCHAR(24) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_demand_state_history PRIMARY KEY (id),
  CONSTRAINT fk_demand_state_history_demand FOREIGN KEY (demand_id) REFERENCES tn_demand (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_demand_lineage (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  source_type VARCHAR(24) NOT NULL,
  source_public_id BINARY(16) NOT NULL,
  target_type VARCHAR(24) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  lineage_type VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_demand_lineage PRIMARY KEY (id),
  CONSTRAINT uk_demand_lineage_public_id UNIQUE (public_id),
  CONSTRAINT uk_demand_lineage_edge UNIQUE (source_type, source_public_id, target_type, target_public_id, lineage_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_audit_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  target_type VARCHAR(24) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  target_version_id BINARY(16) NOT NULL,
  audit_kind VARCHAR(24) NOT NULL,
  state VARCHAR(16) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  assignee_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_audit_task PRIMARY KEY (id),
  CONSTRAINT uk_audit_task_public_id UNIQUE (public_id),
  CONSTRAINT uk_audit_task_target_version UNIQUE (audit_kind, target_type, target_public_id, target_version_id),
  INDEX ix_audit_task_queue (state, priority, created_at, public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_audit_result (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  note TEXT NULL,
  reviewer_id BIGINT UNSIGNED NOT NULL,
  decided_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_audit_result PRIMARY KEY (id),
  CONSTRAINT uk_audit_result_task UNIQUE (task_id),
  CONSTRAINT fk_audit_result_task FOREIGN KEY (task_id) REFERENCES tn_audit_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_price_object (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  target_type VARCHAR(24) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  mode VARCHAR(24) NOT NULL,
  final_amount DECIMAL(12,2) NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_price_object PRIMARY KEY (id),
  CONSTRAINT uk_price_object_public_id UNIQUE (public_id),
  CONSTRAINT uk_price_object_target UNIQUE (target_type, target_public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_price_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  price_object_id BIGINT UNSIGNED NOT NULL,
  before_json JSON NULL,
  after_json JSON NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_price_history PRIMARY KEY (id),
  CONSTRAINT fk_price_history_price_object FOREIGN KEY (price_object_id) REFERENCES tn_price_object (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_file_object (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  sha256 BINARY(32) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  state VARCHAR(16) NOT NULL,
  detected_mime VARCHAR(127) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_file_object PRIMARY KEY (id),
  CONSTRAINT uk_file_object_public_id UNIQUE (public_id),
  CONSTRAINT uk_file_object_digest UNIQUE (sha256, size_bytes),
  CONSTRAINT uk_file_object_key UNIQUE (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_file_upload_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  state VARCHAR(16) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_file_upload_session PRIMARY KEY (id),
  CONSTRAINT uk_file_upload_session_public_id UNIQUE (public_id),
  CONSTRAINT fk_file_upload_session_user FOREIGN KEY (owner_id) REFERENCES tn_user (id),
  INDEX ix_file_upload_session_expiry (state, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_file_relation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  file_object_id BIGINT UNSIGNED NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(24) NULL,
  target_public_id BINARY(16) NULL,
  visibility VARCHAR(24) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_file_relation PRIMARY KEY (id),
  CONSTRAINT uk_file_relation_public_id UNIQUE (public_id),
  CONSTRAINT fk_file_relation_file_object FOREIGN KEY (file_object_id) REFERENCES tn_file_object (id),
  CONSTRAINT fk_file_relation_user FOREIGN KEY (owner_id) REFERENCES tn_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_notification (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  recipient_id BIGINT UNSIGNED NOT NULL,
  notification_type VARCHAR(64) NOT NULL,
  message VARCHAR(500) NOT NULL,
  read_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_notification PRIMARY KEY (id),
  CONSTRAINT uk_notification_public_id UNIQUE (public_id),
  CONSTRAINT fk_notification_user FOREIGN KEY (recipient_id) REFERENCES tn_user (id),
  INDEX ix_notification_user (recipient_id, read_at, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_operation_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id BIGINT UNSIGNED NULL,
  action VARCHAR(96) NOT NULL,
  target JSON NOT NULL,
  trace_id VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_operation_audit PRIMARY KEY (id),
  INDEX ix_operation_audit_action_time (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id BINARY(16) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BINARY(16) NOT NULL,
  event_type VARCHAR(96) NOT NULL,
  schema_version SMALLINT UNSIGNED NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_outbox_event PRIMARY KEY (id),
  CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
  INDEX ix_outbox_event_due (status, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_event_consumption (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  consumer_name VARCHAR(64) NOT NULL,
  event_id BINARY(16) NOT NULL,
  consumed_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_event_consumption PRIMARY KEY (id),
  CONSTRAINT uk_event_consumption_pair UNIQUE (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_idempotency (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id BIGINT UNSIGNED NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  http_method VARCHAR(8) NOT NULL,
  request_path VARCHAR(255) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  response_status SMALLINT UNSIGNED NULL,
  response_body JSON NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_idempotency PRIMARY KEY (id),
  CONSTRAINT uk_idempotency_request UNIQUE (actor_id, http_method, request_path, idempotency_key),
  INDEX ix_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_system_config (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  config_key VARCHAR(128) NOT NULL,
  value_json JSON NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_system_config PRIMARY KEY (id),
  CONSTRAINT uk_system_config_key UNIQUE (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_feature_flag (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  flag_key VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  rule_json JSON NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_feature_flag PRIMARY KEY (id),
  CONSTRAINT uk_feature_flag_key UNIQUE (flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_ai_suggestion (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  public_id BINARY(16) NOT NULL,
  target_type VARCHAR(24) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  suggestion JSON NOT NULL,
  decision VARCHAR(24) NULL,
  decided_by BIGINT UNSIGNED NULL,
  decided_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_ai_suggestion PRIMARY KEY (id),
  CONSTRAINT uk_ai_suggestion_public_id UNIQUE (public_id),
  INDEX ix_ai_suggestion_target (target_type, target_public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tn_search_document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  target_type VARCHAR(24) NOT NULL,
  target_public_id BINARY(16) NOT NULL,
  title VARCHAR(120) NOT NULL,
  summary TEXT NULL,
  body LONGTEXT NULL,
  visibility VARCHAR(24) NOT NULL,
  indexed_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_search_document PRIMARY KEY (id),
  CONSTRAINT uk_search_document_target UNIQUE (target_type, target_public_id),
  FULLTEXT INDEX ix_search_document_text (title, summary, body)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
