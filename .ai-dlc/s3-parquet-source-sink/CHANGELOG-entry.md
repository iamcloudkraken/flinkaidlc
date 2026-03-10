## [Unreleased]

### Added
- S3 (Parquet) source and sink support for pipelines alongside existing Kafka (Avro) connector
- `PipelineSource` and `PipelineSink` extended with single-table inheritance: `KafkaPipelineSource`, `KafkaPipelineSource`, `S3PipelineSource`, `S3PipelineSink`
- `ConnectorType` enum (`KAFKA`, `S3`) and `S3AuthType` enum (`IAM_ROLE`, `ACCESS_KEY`)
- `ColumnDefinition` record for user-supplied Parquet column schema (`name`, `type`)
- Flyway migration `V4__add_s3_source_sink_support.sql`: discriminator columns + S3-specific columns on `pipeline_sources` and `pipeline_sinks`
- `FlinkSqlGenerator` now produces `filesystem` connector DDL with `format=parquet` and `s3a://` path for S3 sources and sinks
- S3 sink rolling policy fixed at 128 MB / 5 min (automatic, not user-configurable)
- `FlinkDeploymentBuilder` injects `s3.access-key` / `s3.secret-key` into Flink deployment `flinkConfiguration` when ACCESS_KEY auth is used
- `POST /api/v1/pipelines` accepts polymorphic source/sink payloads with `"type": "S3"` or `"type": "KAFKA"`
- `GET /api/v1/pipelines/{id}` returns `sourceType`/`sinkType` discriminator and all S3 fields (bucket, prefix, authType, columns)
- Pipeline creation form: source/sink type toggle (Kafka / S3) with S3-specific fields (bucket, prefix, auth, column editor)
- Pipeline detail page: S3 source/sink cards showing bucket, prefix, auth type, columns (secret key never displayed)
- `flink-parquet` and `flink-s3-fs-hadoop` 1.18.1 dependencies in `pom.xml`
- `FlinkSqlGeneratorS3Test` with 6 test cases covering S3 DDL generation, rolling policy, and partition columns

### Changed
- `PipelineSourceRequest` and `PipelineSinkRequest` refactored to sealed interfaces with Jackson `@JsonTypeInfo`; `defaultImpl` preserves backward compatibility for existing Kafka-only API consumers
- `PipelineService` uses Java sealed interface switch pattern matching for source/sink entity construction; schema registry validation skipped for S3 sources
- `LocalDataSeeder` updated to use concrete `KafkaPipelineSource` / `KafkaPipelineSink` after abstract base class refactoring
- Existing Kafka-only columns (`topic`, `bootstrap_servers`, etc.) made nullable in DB to support S3 rows in the same table

### Fixed
- No regressions — all existing Kafka pipeline tests pass unchanged
