ALTER TABLE tn_file_upload_session
  ADD COLUMN file_object_id BIGINT UNSIGNED NULL AFTER owner_id,
  ADD COLUMN relation_public_id BINARY(16) NULL AFTER file_object_id;

UPDATE tn_file_upload_session s
JOIN tn_file_object f ON f.object_key=s.object_key
SET s.file_object_id=f.id;

INSERT INTO tn_file_relation(public_id,file_object_id,owner_id,target_type,target_public_id,visibility,version,
                             created_at,updated_at)
SELECT UUID_TO_BIN(UUID()),s.file_object_id,s.owner_id,NULL,NULL,'OWNER_ONLY',0,MIN(s.created_at),MAX(s.updated_at)
FROM tn_file_upload_session s
LEFT JOIN tn_file_relation r ON r.file_object_id=s.file_object_id AND r.owner_id=s.owner_id
WHERE r.id IS NULL
GROUP BY s.file_object_id,s.owner_id;

UPDATE tn_file_upload_session s
SET s.relation_public_id=(
  SELECT r.public_id FROM tn_file_relation r
  WHERE r.file_object_id=s.file_object_id AND r.owner_id=s.owner_id
  ORDER BY r.id LIMIT 1
);

ALTER TABLE tn_file_upload_session
  MODIFY COLUMN file_object_id BIGINT UNSIGNED NOT NULL,
  MODIFY COLUMN relation_public_id BINARY(16) NOT NULL,
  ADD CONSTRAINT fk_file_upload_session_object FOREIGN KEY (file_object_id) REFERENCES tn_file_object(id),
  ADD INDEX ix_file_upload_session_object (file_object_id);
