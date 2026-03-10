-- Make existing Kafka-only NOT NULL columns nullable to allow S3 rows
ALTER TABLE pipeline_sources
  ALTER COLUMN topic DROP NOT NULL,
  ALTER COLUMN bootstrap_servers DROP NOT NULL,
  ALTER COLUMN consumer_group DROP NOT NULL,
  ALTER COLUMN startup_mode DROP NOT NULL,
  ALTER COLUMN schema_registry_url DROP NOT NULL,
  ALTER COLUMN avro_subject DROP NOT NULL;

ALTER TABLE pipeline_sources
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'KAFKA',
  ADD COLUMN s3_bucket VARCHAR(255),
  ADD COLUMN s3_prefix VARCHAR(500),
  ADD COLUMN s3_partitioned BOOLEAN DEFAULT FALSE,
  ADD COLUMN s3_auth_type VARCHAR(20),
  ADD COLUMN s3_access_key VARCHAR(255),
  ADD COLUMN s3_secret_key VARCHAR(500),
  ADD COLUMN columns JSONB DEFAULT '[]';

ALTER TABLE pipeline_sinks
  ALTER COLUMN topic DROP NOT NULL,
  ALTER COLUMN bootstrap_servers DROP NOT NULL,
  ALTER COLUMN schema_registry_url DROP NOT NULL,
  ALTER COLUMN avro_subject DROP NOT NULL,
  ALTER COLUMN partitioner DROP NOT NULL,
  ALTER COLUMN delivery_guarantee DROP NOT NULL;

ALTER TABLE pipeline_sinks
  ADD COLUMN sink_type VARCHAR(20) NOT NULL DEFAULT 'KAFKA',
  ADD COLUMN s3_bucket VARCHAR(255),
  ADD COLUMN s3_prefix VARCHAR(500),
  ADD COLUMN s3_partitioned BOOLEAN DEFAULT FALSE,
  ADD COLUMN s3_auth_type VARCHAR(20),
  ADD COLUMN s3_access_key VARCHAR(255),
  ADD COLUMN s3_secret_key VARCHAR(500),
  ADD COLUMN columns JSONB DEFAULT '[]',
  ADD COLUMN s3_partition_columns JSONB DEFAULT '[]';
