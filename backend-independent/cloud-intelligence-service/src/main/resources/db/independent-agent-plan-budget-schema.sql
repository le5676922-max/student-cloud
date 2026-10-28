-- Owner-scoped mutex for bounded preview creation. Register as intelligence V7.
CREATE TABLE sc_agent_plan_guard (
  account_id BIGINT NOT NULL PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
