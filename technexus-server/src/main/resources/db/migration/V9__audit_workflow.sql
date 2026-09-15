ALTER TABLE tn_demand
  ADD COLUMN pending_review_version_id BINARY(16) NULL AFTER visibility;

UPDATE tn_demand
SET pending_review_version_id = UUID_TO_BIN(UUID(), 1)
WHERE state = 'PENDING_REVIEW' AND pending_review_version_id IS NULL;

ALTER TABLE tn_demand
  ADD CONSTRAINT ck_demand_review_version
  CHECK ((state = 'PENDING_REVIEW' AND pending_review_version_id IS NOT NULL)
      OR (state <> 'PENDING_REVIEW' AND pending_review_version_id IS NULL));

INSERT INTO tn_audit_task
  (public_id, target_type, target_public_id, target_version_id, audit_kind, state,
   priority, assignee_id, version, created_at, updated_at)
SELECT UUID_TO_BIN(UUID(), 1), 'CONTENT', article.public_id, version.public_id, 'CONTENT', 'PENDING',
       0, NULL, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tn_article article
JOIN tn_article_version version ON version.article_id = article.id AND version.state = 'PENDING_REVIEW'
WHERE NOT EXISTS (
  SELECT 1 FROM tn_audit_task task
  WHERE task.audit_kind = 'CONTENT' AND task.target_type = 'CONTENT'
    AND task.target_public_id = article.public_id AND task.target_version_id = version.public_id
);

INSERT INTO tn_audit_task
  (public_id, target_type, target_public_id, target_version_id, audit_kind, state,
   priority, assignee_id, version, created_at, updated_at)
SELECT UUID_TO_BIN(UUID(), 1), 'CONTENT', post.public_id, version.public_id, 'CONTENT', 'PENDING',
       0, NULL, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tn_post post
JOIN tn_post_version version ON version.post_id = post.id AND version.state = 'PENDING_REVIEW'
WHERE NOT EXISTS (
  SELECT 1 FROM tn_audit_task task
  WHERE task.audit_kind = 'CONTENT' AND task.target_type = 'CONTENT'
    AND task.target_public_id = post.public_id AND task.target_version_id = version.public_id
);

INSERT INTO tn_audit_task
  (public_id, target_type, target_public_id, target_version_id, audit_kind, state,
   priority, assignee_id, version, created_at, updated_at)
SELECT UUID_TO_BIN(UUID(), 1), 'DEMAND', demand.public_id, demand.pending_review_version_id, 'DEMAND', 'PENDING',
       0, NULL, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tn_demand demand
WHERE demand.state = 'PENDING_REVIEW'
  AND NOT EXISTS (
    SELECT 1 FROM tn_audit_task task
    WHERE task.audit_kind = 'DEMAND' AND task.target_type = 'DEMAND'
      AND task.target_public_id = demand.public_id
      AND task.target_version_id = demand.pending_review_version_id
  );
