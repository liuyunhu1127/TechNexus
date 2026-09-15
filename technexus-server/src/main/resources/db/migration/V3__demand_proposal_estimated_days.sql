ALTER TABLE tn_demand_proposal
  ADD COLUMN estimated_days INT UNSIGNED NOT NULL DEFAULT 1 AFTER tech_stack;

ALTER TABLE tn_demand_proposal ALTER COLUMN estimated_days DROP DEFAULT;
