CREATE TABLE pipeline_deployments (
  pipeline_id UUID PRIMARY KEY REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  version INT NOT NULL DEFAULT 1,
  k8s_resource_name VARCHAR(255),
  configmap_name VARCHAR(255),
  flink_job_id VARCHAR(255),
  lifecycle_state VARCHAR(30),
  job_state VARCHAR(20),
  last_savepoint_path TEXT,
  error_message TEXT,
  last_synced_at TIMESTAMPTZ
);
