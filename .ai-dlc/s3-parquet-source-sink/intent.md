---
workflow: default
git:
  change_strategy: intent
  auto_merge: true
  auto_squash: false
announcements: [changelog, release-notes, social-posts, blog-draft]
created: 2026-03-10
status: active
epic: ""
---

# S3 (Parquet) Source and Sink Support

## Problem
Pipeline creation supports only Kafka (Avro) as a source and sink type. Users who want to read from or write to S3 in Parquet format have no way to configure this through the platform — they would need to hand-write Flink SQL manually and bypass the pipeline management API entirely.

## Solution
Extend the platform to support S3 (Parquet) as a first-class source and sink type alongside Kafka. When a user creates a pipeline, they choose between Kafka (Avro) and S3 (Parquet) for each source and sink independently. The platform generates the correct Flink SQL `CREATE TABLE` DDL, stores the configuration in the database, and for pipelines using access key authentication, injects S3 credentials into the Flink deployment at runtime. Both the REST API and the React web UI are updated.

## Domain Model

### Entities

- **PipelineSource**: Extended with `source_type` discriminator (KAFKA or S3). Kafka sources retain all existing fields. S3 sources use: bucket, prefix, partitioned flag, authType (IAM_ROLE or ACCESS_KEY), accessKey (nullable), secretKey (nullable), columns JSONB (`[{"name":"col","type":"STRING"}]`).
- **PipelineSink**: Extended with `sink_type` discriminator (KAFKA or S3). S3 sinks use: bucket, prefix, authType, accessKey, secretKey, columns JSONB, partitionColumns JSONB (optional list of column names to partition output by).
- **Pipeline**: Unchanged. Still owns sources and sinks via one-to-many collections.
- **PipelineDeployment**: Extended behavior — FlinkDeploymentBuilder injects S3 credentials into `flinkConfiguration` when ACCESS_KEY auth is used.

### Relationships
- Pipeline has-many PipelineSource (cascade delete)
- Pipeline has-many PipelineSink (cascade delete)

### Data Sources
- **PostgreSQL** (primary store): Single-table inheritance — `source_type` and `sink_type` discriminator columns added; S3-specific columns added as nullable.
- **Kubernetes API** (via Fabric8): FlinkDeployment CRD — `flinkConfiguration` map extended with `s3.access-key` / `s3.secret-key` when needed.

### Data Gaps
- S3 credentials stored plaintext for MVP (same as Kafka bootstrap servers). Encryption at rest is a future enhancement.
- Column schema for S3 sources/sinks is user-supplied — no schema inference from Parquet file headers in MVP.

## Success Criteria
- [ ] `POST /api/v1/pipelines` accepts S3 source with `type: S3`, bucket, prefix, columns, and auth (IAM or access key) — returns 201
- [ ] `POST /api/v1/pipelines` accepts S3 sink with `type: S3`, bucket, prefix, columns — returns 201
- [ ] `GET /api/v1/pipelines/{id}` returns S3 source and sink fields correctly (bucket, prefix, authType, columns)
- [ ] `FlinkSqlGenerator` produces valid Flink SQL `CREATE TABLE` DDL using `connector=filesystem`, `format=parquet` for both S3 sources and sinks
- [ ] For pipelines with S3 + ACCESS_KEY auth, `FlinkDeploymentBuilder` injects `s3.access-key` and `s3.secret-key` into the Flink deployment flinkConfiguration
- [ ] Pipeline creation form shows Source Type toggle (Kafka / S3) and renders S3-specific fields when S3 is selected
- [ ] Pipeline creation form shows Sink Type toggle (Kafka / S3) and renders S3-specific fields when S3 is selected
- [ ] Pipeline detail page renders S3 source/sink cards showing bucket, prefix, auth type, and columns
- [ ] All existing Kafka pipeline tests pass without regression
- [ ] `pom.xml` includes `flink-parquet` and `flink-s3-fs-hadoop` dependencies

## Context
- Flink filesystem connector uses `s3a://` URI scheme (Hadoop-based). S3 credentials cannot be passed as SQL connector properties — they must be in the Flink deployment config.
- For partitioned S3 sources (Hive-style `year=2024/month=01/`), the `CREATE TABLE` DDL includes `PARTITIONED BY (...)` and Flink auto-discovers directories. No Hive metastore needed.
- Sink rolling policy is fixed at 5 minutes / 128 MB — not user-configurable.
- Auth options: IAM Role (no credentials in DB or deployment config, pod assumes role) or Access Key + Secret (stored in DB, injected into Flink deployment at deploy time).
- pom.xml currently has no Flink dependencies — Flink connectors are managed by the Flink pod image. The `flink-parquet` and `flink-s3-fs-hadoop` additions are for the SQL generation code path (compile-time only).
