ALTER TABLE tn_tag
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER merged_into_id;

CREATE TABLE tn_tag_alias (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  alias_name VARCHAR(48) NOT NULL,
  tag_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT pk_tag_alias PRIMARY KEY (id),
  CONSTRAINT uk_tag_alias_name UNIQUE (alias_name),
  CONSTRAINT fk_tag_alias_tag FOREIGN KEY (tag_id) REFERENCES tn_tag (id),
  INDEX ix_tag_alias_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
