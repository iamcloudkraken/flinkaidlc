CREATE TABLE pipelines (
  pipeline_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(tenant_id),
  name VARCHAR(255) NOT NULL,
  description TEXT,
  sql_query TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  parallelism INT NOT NULL DEFAULT 1,
  checkpoint_interval_ms BIGINT NOT NULL DEFAULT 60000,
  upgrade_mode VARCHAR(20) NOT NULL DEFAULT 'SAVEPOINT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_sources (
  source_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pipeline_id UUID NOT NULL REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  table_name VARCHAR(255) NOT NULL,
  topic VARCHAR(255) NOT NULL,
  bootstrap_servers TEXT NOT NULL,
  consumer_group VARCHAR(255) NOT NULL,
  startup_mode VARCHAR(20) NOT NULL DEFAULT 'GROUP_OFFSETS',
  schema_registry_url TEXT NOT NULL,
  avro_subject VARCHAR(255) NOT NULL,
  watermark_column VARCHAR(255),
  watermark_delay_ms BIGINT DEFAULT 5000,
  extra_properties JSONB DEFAULT '{}'
);

CREATE TABLE pipeline_sinks (
  sink_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pipeline_id UUID NOT NULL REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  table_name VARCHAR(255) NOT NULL,
  topic VARCHAR(255) NOT NULL,
  bootstrap_servers TEXT NOT NULL,
  schema_registry_url TEXT NOT NULL,
  avro_subject VARCHAR(255) NOT NULL,
  partitioner VARCHAR(20) NOT NULL DEFAULT 'DEFAULT',
  delivery_guarantee VARCHAR(20) NOT NULL DEFAULT 'AT_LEAST_ONCE'
);
