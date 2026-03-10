---
status: success
error_message: ""
---

# Discovery Results

## Domain Model Summary

### Entities

- **Pipeline**: Core entity. Fields: pipelineId, tenantId, name, description, sqlQuery, status, parallelism, checkpointIntervalMs, upgradeMode, createdAt, updatedAt. Has-many PipelineSource, has-many PipelineSink, has-one PipelineDeployment.
- **PipelineSource** (currently Kafka-only): sourceId, pipeline FK, tableName + all Kafka fields (topic, bootstrapServers, consumerGroup, startupMode, schemaRegistryUrl, avroSubject, watermarkColumn, watermarkDelayMs, extraProperties JSONB). Needs: source_type discriminator + S3-specific fields.
- **PipelineSink** (currently Kafka-only): sinkId, pipeline FK, tableName + all Kafka fields (topic, bootstrapServers, schemaRegistryUrl, avroSubject, partitioner, deliveryGuarantee). Needs: sink_type discriminator + S3-specific fields.
- **PipelineDeployment**: Holds Flink/K8s runtime state (k8sResourceName, flinkJobId, lifecycleState, jobState, lastSavepointPath, errorMessage).

### Relationships
- Pipeline has many PipelineSource (cascade delete)
- Pipeline has many PipelineSink (cascade delete)
- Pipeline has one PipelineDeployment (cascade delete)

### Data Sources
- **PostgreSQL** (primary): All pipeline data stored here. Flyway manages migrations.
- **Kubernetes API** (via Fabric8): FlinkDeployment CRDs managed here. FlinkDeploymentBuilder constructs the CRD.
- **Confluent Schema Registry** (validation only for Kafka): PipelineService validates schema registry URLs during create/update.

### Data Gaps
- **No S3/Parquet support exists**: Must add from scratch — new DB columns, new entity subclasses, new SQL generation, new deployment config injection, new frontend form fields.
- **AWS credentials cannot go in SQL**: Must be injected into FlinkDeployment's `flinkConfiguration` map by FlinkDeploymentBuilder — a new concern for that class.
- **Column schema for S3**: Kafka uses Avro schema registry for schema. S3 Parquet has no schema registry — user must supply column definitions explicitly (name + SQL type). Store as JSONB in DB.

## Key Findings

1. **FlinkSqlGenerator** is the central extension point — add S3 source/sink generation methods alongside existing Kafka methods. No other generator class exists.
2. **FlinkDeploymentBuilder** already handles S3 for checkpoints (`flink.state.s3-bucket` config) — extend it to inject `s3.access-key`/`s3.secret-key` when a pipeline has S3 sources/sinks with ACCESS_KEY auth.
3. **JPA single-table inheritance** is the right fit — add `source_type`/`sink_type` discriminator columns + nullable S3 columns via a single Flyway migration. Creates `KafkaPipelineSource`, `S3PipelineSource`, `KafkaPipelineSink`, `S3PipelineSink` subclasses.
4. **API request DTOs** need polymorphic deserialization — `PipelineSourceRequest` becomes a sealed interface with `KafkaSourceRequest` and `S3SourceRequest` implementations, discriminated by a `type` field in the JSON.
5. **Frontend** is a 5-step wizard (`PipelineEditorPage.tsx`). Step 1 (Sources) and Step 2 (Sinks) need a source/sink type toggle (Kafka ↔ S3) that conditionally renders the appropriate fields. Step 4 (Review) and `PipelineDetailPage.tsx` need to display S3 config.
6. **pom.xml** needs two new dependencies: `flink-parquet` and `flink-s3-fs-hadoop` (both Flink 1.18.1).
7. **S3 auth**: IAM Role requires nothing in SQL or deployment config. Access Key requires injecting `s3.access-key` + `s3.secret-key` into the FlinkDeployment `flinkConfiguration`.
8. **Hive-style partitions**: Handled automatically by Flink filesystem connector when `PARTITIONED BY (...)` is added to the `CREATE TABLE`. No extra config needed — the UI just needs a checkbox to indicate partitioned paths.
9. **Sink rolling**: Fixed at `sink.rolling-policy.rollover-interval = '5 min'` and `sink.rolling-policy.file-size = '128MB'`. Not configurable per pipeline. Hard-coded in FlinkSqlGenerator.
10. **S3 columns** for both source and sink: stored as JSONB (`[{"name":"user_id","type":"STRING"},...]`). A dedicated `columns` column is cleaner than overloading `extra_properties`.

## Open Questions

- Should secret key be stored encrypted at rest? (Current pattern stores Kafka bootstrap servers plaintext — S3 secrets are more sensitive.) MVP: plaintext with TODO comment.
- What Flink version is the platform targeting? pom.xml lists Spring Boot 3.3.10 but no explicit Flink version — the Flink pod image version determines compatibility. Assuming 1.18.x.
- Does the SQL validator (JSQLParser) need to accept `PARTITIONED BY` syntax? Need to verify JSQLParser doesn't reject S3 `CREATE TABLE` DDL (it shouldn't since we generate DDL separately from the user's INSERT query).

## Mockups Generated

- discovery.md § "UI Mockup: Source Step — Kafka Selected" — existing Kafka form with new type toggle
- discovery.md § "UI Mockup: Source Step — S3 Selected" — S3-specific form fields
- discovery.md § "UI Mockup: Sink Step — S3 Selected" — S3 sink fields with rolling policy note
- discovery.md § "UI Mockup: Pipeline Detail — S3 Source/Sink Display" — detail page S3 card layout
