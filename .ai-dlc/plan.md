# Implementation Plan: unit-01-s3-parquet-source-sink

## Key Discoveries
- Highest existing migration: V3. New migration must be V4.
- `PipelineSource`/`PipelineSink` are concrete — must become abstract with SINGLE_TABLE inheritance.
- Kafka-only columns (`topic`, `bootstrap_servers`, etc.) are `NOT NULL` — V4 migration MUST drop NOT NULL first.
- `PipelineService` directly instantiates `new PipelineSource()` / `new PipelineSink()` (4 sites: create + update for each).
- Schema registry validation is called for all sources — must skip for S3.
- `FlinkSqlGenerator.generate()` iterates `List<PipelineSource>` calling private methods — add type dispatch.
- `FlinkDeploymentBuilder.buildSpec()` has `flinkConfig` map — inject S3 credentials after base config.
- `PipelineDetailResponse` returns raw entity lists — `@JsonTypeInfo` on entity handles serialization.
- `defaultImpl = KafkaSourceRequest.class` on `@JsonTypeInfo` preserves backward compat (existing tests have no `"type"` field).
- `FlinkSqlGeneratorTest` and `FlinkOrchestrationServiceImplTest` instantiate `PipelineSource`/`PipelineSink` directly — must update to `KafkaPipelineSource`/`KafkaPipelineSink`.
- Frontend uses `KafkaSource`/`KafkaSink` types — must add discriminated unions.

## Ordered Implementation Steps (28 steps across 27 files)

### Phase 1: Database Migration

**Step 1** — CREATE `src/main/resources/db/migration/V4__add_s3_source_sink_support.sql`
```sql
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
```

### Phase 2: New Domain Types

**Step 2** — CREATE `src/main/java/com/flinkaidlc/platform/domain/ConnectorType.java`
```java
package com.flinkaidlc.platform.domain;
public enum ConnectorType { KAFKA, S3 }
```

**Step 3** — CREATE `src/main/java/com/flinkaidlc/platform/domain/S3AuthType.java`
```java
package com.flinkaidlc.platform.domain;
public enum S3AuthType { IAM_ROLE, ACCESS_KEY }
```

**Step 4** — CREATE `src/main/java/com/flinkaidlc/platform/domain/ColumnDefinition.java`
```java
package com.flinkaidlc.platform.domain;
public record ColumnDefinition(String name, String type) {}
```

### Phase 3: JPA Entity Refactoring

**Step 5** — MODIFY `src/main/java/com/flinkaidlc/platform/domain/PipelineSource.java`
- Make abstract
- Add `@Inheritance(strategy = InheritanceType.SINGLE_TABLE)`
- Add `@DiscriminatorColumn(name = "source_type", discriminatorType = DiscriminatorType.STRING)`
- Add `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sourceType", visible = true)` + `@JsonSubTypes`
- Keep: sourceId, pipeline (back-ref with @JsonIgnore), tableName
- REMOVE: all Kafka fields (topic, bootstrapServers, consumerGroup, startupMode, schemaRegistryUrl, avroSubject, watermarkColumn, watermarkDelayMs, extraProperties)

**Step 6** — CREATE `src/main/java/com/flinkaidlc/platform/domain/KafkaPipelineSource.java`
- `@Entity @DiscriminatorValue("KAFKA") class KafkaPipelineSource extends PipelineSource`
- All former Kafka fields with same @Column annotations but `nullable = false` removed (DB column is now nullable)

**Step 7** — CREATE `src/main/java/com/flinkaidlc/platform/domain/S3PipelineSource.java`
- `@Entity @DiscriminatorValue("S3") class S3PipelineSource extends PipelineSource`
- Fields: bucket, prefix, partitioned, authType (S3AuthType), accessKey, secretKey (TODO: encrypt), columns (List<ColumnDefinition> JSONB)

**Step 8** — MODIFY `src/main/java/com/flinkaidlc/platform/domain/PipelineSink.java`
- Same pattern as PipelineSource
- Keep: sinkId, pipeline (back-ref), tableName
- REMOVE: topic, bootstrapServers, schemaRegistryUrl, avroSubject, partitioner, deliveryGuarantee

**Step 9** — CREATE `src/main/java/com/flinkaidlc/platform/domain/KafkaPipelineSink.java`
- `@Entity @DiscriminatorValue("KAFKA") class KafkaPipelineSink extends PipelineSink`
- Former Kafka fields: topic, bootstrapServers, schemaRegistryUrl, avroSubject, partitioner (Partitioner), deliveryGuarantee (DeliveryGuarantee)

**Step 10** — CREATE `src/main/java/com/flinkaidlc/platform/domain/S3PipelineSink.java`
- `@Entity @DiscriminatorValue("S3") class S3PipelineSink extends PipelineSink`
- Fields: bucket, prefix, partitioned, authType, accessKey, secretKey, columns (List<ColumnDefinition>), s3PartitionColumns (List<String> JSONB)

### Phase 4: API Request DTOs

**Step 11** — MODIFY `src/main/java/com/flinkaidlc/platform/pipeline/PipelineSourceRequest.java`
- Replace record with sealed interface:
```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = KafkaSourceRequest.class)
@JsonSubTypes({
  @JsonSubTypes.Type(value = KafkaSourceRequest.class, name = "KAFKA"),
  @JsonSubTypes.Type(value = S3SourceRequest.class, name = "S3")
})
public sealed interface PipelineSourceRequest permits KafkaSourceRequest, S3SourceRequest {
    String tableName();
}
```

**Step 12** — CREATE `src/main/java/com/flinkaidlc/platform/pipeline/KafkaSourceRequest.java`
- Same fields as the original PipelineSourceRequest record, implements PipelineSourceRequest

**Step 13** — CREATE `src/main/java/com/flinkaidlc/platform/pipeline/S3SourceRequest.java`
- tableName, bucket, prefix, partitioned, authType (S3AuthType), accessKey, secretKey, columns (List<ColumnDefinition>)
- `@AssertTrue` method for credentials validation when authType == ACCESS_KEY

**Step 14** — MODIFY `src/main/java/com/flinkaidlc/platform/pipeline/PipelineSinkRequest.java`
- Same sealed interface pattern with `defaultImpl = KafkaSinkRequest.class`

**Step 15** — CREATE `src/main/java/com/flinkaidlc/platform/pipeline/KafkaSinkRequest.java`
- Former PipelineSinkRequest record fields, implements PipelineSinkRequest

**Step 16** — CREATE `src/main/java/com/flinkaidlc/platform/pipeline/S3SinkRequest.java`
- tableName, bucket, prefix, partitioned, authType, accessKey, secretKey, columns, s3PartitionColumns (List<String>)
- `@AssertTrue` credentials validation

### Phase 5: Service Layer

**Step 17** — MODIFY `src/main/java/com/flinkaidlc/platform/pipeline/PipelineService.java`

a) Schema registry validation (createPipeline + updatePipeline — both sites):
```java
// Only validate Kafka sources/sinks
for (PipelineSourceRequest source : request.sources()) {
    if (source instanceof KafkaSourceRequest kafka) {
        schemaRegistryValidationService.validate(kafka.schemaRegistryUrl(), kafka.avroSubject());
    }
}
```

b) Source entity construction (createPipeline + updatePipeline):
```java
PipelineSource source = switch (sourceReq) {
    case KafkaSourceRequest kafka -> { KafkaPipelineSource s = new KafkaPipelineSource(); /* set fields */ yield s; }
    case S3SourceRequest s3 -> { S3PipelineSource s = new S3PipelineSource(); /* set fields */ yield s; }
};
```

c) Sink entity construction — same pattern.

### Phase 6: SQL Generator

**Step 18** — MODIFY `src/main/java/com/flinkaidlc/platform/orchestration/FlinkSqlGenerator.java`

a) `generate()` method: add instanceof dispatch for S3 vs Kafka
b) Rename `generateSourceDdl()` → `generateKafkaSourceDdl(KafkaPipelineSource source)`
c) Rename `generateSinkDdl()` → `generateKafkaSinkDdl(KafkaPipelineSink sink)`
d) Add `generateS3SourceDdl(S3PipelineSource source)`:
   - path = "s3a://{bucket}/{prefix}"
   - columns from List<ColumnDefinition>
   - WITH: connector=filesystem, path, format=parquet, source.path.regex-pattern=.*\.parquet$
e) Add `generateS3SinkDdl(S3PipelineSink sink)`:
   - WITH: connector=filesystem, path, format=parquet
   - sink.rolling-policy.file-size=128MB
   - sink.rolling-policy.rollover-interval=5 min
   - sink.rolling-policy.check-interval=1 min
   - PARTITIONED BY if s3PartitionColumns non-empty

### Phase 7: Deployment Builder

**Step 19** — MODIFY `src/main/java/com/flinkaidlc/platform/orchestration/FlinkDeploymentBuilder.java`

In `buildSpec()` after base flinkConfig is built:
```java
pipeline.getSources().stream()
    .filter(s -> s instanceof S3PipelineSource)
    .map(s -> (S3PipelineSource) s)
    .filter(s -> s.getAuthType() == S3AuthType.ACCESS_KEY)
    .findFirst()
    .ifPresent(s -> {
        flinkConfig.put("s3.access-key", s.getAccessKey());
        flinkConfig.put("s3.secret-key", s.getSecretKey());
    });
pipeline.getSinks().stream()
    .filter(s -> s instanceof S3PipelineSink)
    .map(s -> (S3PipelineSink) s)
    .filter(s -> s.getAuthType() == S3AuthType.ACCESS_KEY)
    .findFirst()
    .ifPresent(s -> {
        flinkConfig.putIfAbsent("s3.access-key", s.getAccessKey());
        flinkConfig.putIfAbsent("s3.secret-key", s.getSecretKey());
    });
```

### Phase 8: pom.xml

**Step 20** — MODIFY `pom.xml`
Add inside `<dependencies>`:
```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-parquet</artifactId>
    <version>1.18.1</version>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-s3-fs-hadoop</artifactId>
    <version>1.18.1</version>
</dependency>
```

### Phase 9: Frontend

**Step 21** — MODIFY `frontend/src/api/pipelines.ts`
- Add: `ConnectorType`, `S3AuthType`, `ColumnDefinition`, `S3Source`, `S3Sink`, `PipelineSourceConfig`, `PipelineSinkConfig` types
- Add `type: 'KAFKA'` discriminator to existing `KafkaSource`/`KafkaSink` interfaces
- Update `Pipeline.sources` → `PipelineSourceConfig[]`, `Pipeline.sinks` → `PipelineSinkConfig[]`
- Update `CreatePipelineRequest.sources/sinks` to use union types

**Step 22** — MODIFY `frontend/src/pages/PipelineEditorPage.tsx`
- Add defaultS3Source/defaultS3Sink objects
- Update FormState sources/sinks types to use union types
- Step 1 (Sources): Add type radio toggle; render Kafka or S3 fields based on type
- Step 2 (Sinks): Same, plus partition columns multi-select and rolling policy note

**Step 23** — MODIFY `frontend/src/pages/PipelineDetailPage.tsx`
- Sources section: type-dispatch on `source.sourceType === 'S3'` for S3 card vs Kafka card
- Sinks section: same pattern

### Phase 10: Tests

**Step 24** — MODIFY `src/test/java/com/flinkaidlc/platform/orchestration/FlinkSqlGeneratorTest.java`
- `new PipelineSource()` → `new KafkaPipelineSource()`
- `new PipelineSink()` → `new KafkaPipelineSink()`

**Step 25** — MODIFY `src/test/java/com/flinkaidlc/platform/orchestration/FlinkOrchestrationServiceImplTest.java`
- `new PipelineSource()` → `new KafkaPipelineSource()`
- `new PipelineSink()` → `new KafkaPipelineSink()`

**Step 26** — CREATE `src/test/java/com/flinkaidlc/platform/orchestration/FlinkSqlGeneratorS3Test.java`
Tests:
- `generate_s3Source_producesFilesystemConnectorDdl()` — assert connector=filesystem, format=parquet, s3a://
- `generate_s3Sink_producesFilesystemConnectorWithRollingPolicy()` — assert 128MB rolling policy
- `generate_s3SinkPartitioned_includesPartitionedByClause()` — assert PARTITIONED BY
- `generate_mixedKafkaAndS3Sources_producesBothDdls()` — assert both DDLs in same output

**Step 27** — MODIFY `src/test/java/com/flinkaidlc/platform/pipeline/PipelineControllerIntegrationTest.java`
- Add `createPipeline_s3Source_returns201()` test with full S3 source/sink JSON body

## Critical Ordering Dependencies
1. V4 migration FIRST (step 1) — DB must be ready before entities can work
2. New enums/record BEFORE entity classes (steps 2-4 before 5-10)
3. Abstract entities BEFORE service and generator (steps 5-10 before 17-19)
4. Sealed interface DTOs BEFORE service (steps 11-16 before 17)
5. Fix existing tests BEFORE adding new tests (steps 24-25 before 26-27)
6. Backend complete BEFORE frontend (steps 1-19 before 21-23)

## Key Gotchas
- `@Column(nullable = false)` on subclass fields: REMOVE since DB column is now nullable (V4 drops NOT NULL)
- `@DiscriminatorColumn` vs `@Column`: Do NOT add `@Column(name="source_type")` — JPA manages discriminator automatically
- `PipelineDetailResponse` returns entity lists directly — `@JsonTypeInfo` on `PipelineSource` serializes discriminator automatically
- Use `property = "sourceType"` on entity `@JsonTypeInfo` (matches field name) and frontend reads `source.sourceType`
- `s3PartitionColumns` must default to empty list to avoid NPE in generator
