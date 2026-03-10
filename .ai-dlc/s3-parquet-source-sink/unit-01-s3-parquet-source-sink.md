---
status: in_progress
depends_on: []
branch: ai-dlc/s3-parquet-source-sink/01-s3-parquet-source-sink
discipline: fullstack
workflow: ""
ticket: ""
---

# unit-01-s3-parquet-source-sink

## Description
Add S3 (Parquet) as a supported source and sink type across the full stack: database schema, JPA entities, REST API request/response DTOs, Flink SQL generation, Flink deployment credential injection, React pipeline creation form, and pipeline detail display. After this unit, users can create pipelines that read from or write to S3 in Parquet format.

## Discipline
fullstack — this unit spans Spring Boot backend and React/TypeScript frontend.

## Domain Entities
- **PipelineSource**: Extended with `source_type` discriminator (KAFKA/S3) and S3-specific fields
- **PipelineSink**: Extended with `sink_type` discriminator (KAFKA/S3) and S3-specific fields
- **FlinkSqlGenerator**: New methods for S3 source/sink SQL generation
- **FlinkDeploymentBuilder**: Credential injection for ACCESS_KEY auth pipelines

## Data Sources
- PostgreSQL via Flyway migration: add discriminator columns + S3 fields to `pipeline_sources` and `pipeline_sinks`
- Kubernetes FlinkDeployment CRD: `spec.flinkConfiguration` map for S3 credentials

## Technical Specification

### 1. pom.xml — New Dependencies

Add to `pom.xml` (check existing Flink version in the project; if none, use 1.18.1):

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

### 2. Flyway Migration

Create `src/main/resources/db/migration/V{next}__add_s3_source_sink_support.sql`:

```sql
-- Add source type discriminator
ALTER TABLE pipeline_sources
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'KAFKA',
  ADD COLUMN s3_bucket VARCHAR(255),
  ADD COLUMN s3_prefix VARCHAR(500),
  ADD COLUMN s3_partitioned BOOLEAN DEFAULT FALSE,
  ADD COLUMN s3_auth_type VARCHAR(20),
  ADD COLUMN s3_access_key VARCHAR(255),
  ADD COLUMN s3_secret_key VARCHAR(500),
  ADD COLUMN columns JSONB DEFAULT '[]';

-- Add sink type discriminator
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

### 3. Domain Enums

Create `src/main/java/com/flinkaidlc/platform/domain/ConnectorType.java`:
```java
public enum ConnectorType { KAFKA, S3 }
```

Create `src/main/java/com/flinkaidlc/platform/domain/S3AuthType.java`:
```java
public enum S3AuthType { IAM_ROLE, ACCESS_KEY }
```

### 4. JPA Entity Refactoring — PipelineSource

Refactor `PipelineSource.java` to use single-table inheritance:

```java
@Entity
@Table(name = "pipeline_sources")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "source_type", discriminatorType = DiscriminatorType.STRING)
@Getter @Setter @NoArgsConstructor
public abstract class PipelineSource {
    @Id @UuidGenerator
    @Column(name = "source_id", updatable = false, nullable = false)
    private UUID sourceId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(name = "table_name", nullable = false)
    private String tableName;
}
```

Create `KafkaPipelineSource.java`:
```java
@Entity
@DiscriminatorValue("KAFKA")
public class KafkaPipelineSource extends PipelineSource {
    // All existing fields: topic, bootstrapServers, consumerGroup,
    // startupMode, schemaRegistryUrl, avroSubject,
    // watermarkColumn, watermarkDelayMs, extraProperties
}
```

Create `S3PipelineSource.java`:
```java
@Entity
@DiscriminatorValue("S3")
public class S3PipelineSource extends PipelineSource {
    @Column(name = "s3_bucket", nullable = false)
    private String bucket;

    @Column(name = "s3_prefix", nullable = false)
    private String prefix;

    @Column(name = "s3_partitioned", nullable = false)
    private boolean partitioned = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "s3_auth_type", nullable = false)
    private S3AuthType authType;

    @Column(name = "s3_access_key")
    private String accessKey;   // nullable — only for ACCESS_KEY auth

    @Column(name = "s3_secret_key")
    private String secretKey;   // nullable — only for ACCESS_KEY auth
                                // TODO: encrypt at rest

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb", nullable = false)
    private List<ColumnDefinition> columns = new ArrayList<>();
}
```

Do the same refactoring for `PipelineSink.java` → `KafkaPipelineSink` + `S3PipelineSink`. S3PipelineSink adds `s3PartitionColumns: List<String>` in addition to the fields above.

Create a `ColumnDefinition` record: `record ColumnDefinition(String name, String type) {}`

### 5. API Request DTOs — Polymorphic Deserialization

Replace `PipelineSourceRequest` with a sealed-interface-style hierarchy using Jackson:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = KafkaSourceRequest.class, name = "KAFKA"),
  @JsonSubTypes.Type(value = S3SourceRequest.class, name = "S3")
})
public sealed interface PipelineSourceRequest permits KafkaSourceRequest, S3SourceRequest {}
```

`KafkaSourceRequest` — same fields as the current `PipelineSourceRequest`.

`S3SourceRequest`:
```java
public record S3SourceRequest(
  @NotBlank String tableName,
  @NotBlank String bucket,
  @NotBlank String prefix,
  boolean partitioned,
  @NotNull S3AuthType authType,
  String accessKey,    // required if authType == ACCESS_KEY
  String secretKey,    // required if authType == ACCESS_KEY
  @NotEmpty List<ColumnDefinition> columns
) implements PipelineSourceRequest {}
```

Same pattern for `PipelineSinkRequest` → `KafkaSinkRequest` + `S3SinkRequest`.

**Validation**: Add a custom validator (or @AssertTrue method) on `S3SourceRequest` and `S3SinkRequest` that enforces `accessKey` and `secretKey` are non-blank when `authType == ACCESS_KEY`.

### 6. FlinkSqlGenerator — S3 SQL Generation

Add to `FlinkSqlGenerator.java`:

```java
// S3 Source
public String generateS3SourceDdl(S3PipelineSource source) {
    String path = "s3a://" + source.getBucket() + "/" + source.getPrefix();
    String columns = source.getColumns().stream()
        .map(c -> "`" + c.name() + "` " + c.type())
        .collect(joining(",\n  "));

    String partitionClause = "";
    if (source.isPartitioned()) {
        // Partition columns are the last N columns where type is STRING/INT etc.
        // User is responsible for declaring partition columns last in the column list.
        // The entire column list forms the PARTITIONED BY list for the partitioned case.
        // For simplicity in v1: when partitioned=true, add PARTITIONED BY using all columns
        // that have hive-style directory representations (implementation detail for builder).
        partitionClause = ""; // Builder determines partition columns from directory structure
    }

    return String.format("""
        CREATE TABLE `%s` (
          %s
        ) WITH (
          'connector' = 'filesystem',
          'path' = '%s',
          'format' = 'parquet',
          'source.path.regex-pattern' = '.*\\.parquet$'
        );
        """, source.getTableName(), columns, path);
}

// S3 Sink
public String generateS3SinkDdl(S3PipelineSink sink) {
    String path = "s3a://" + sink.getBucket() + "/" + sink.getPrefix();
    String columns = sink.getColumns().stream()
        .map(c -> "`" + c.name() + "` " + c.type())
        .collect(joining(",\n  "));

    String partitionClause = sink.getS3PartitionColumns().isEmpty() ? "" :
        " PARTITIONED BY (" +
        sink.getS3PartitionColumns().stream().map(col -> "`" + col + "`").collect(joining(", ")) +
        ")";

    return String.format("""
        CREATE TABLE `%s` (
          %s
        )%s WITH (
          'connector' = 'filesystem',
          'path' = '%s',
          'format' = 'parquet',
          'sink.rolling-policy.file-size' = '128MB',
          'sink.rolling-policy.rollover-interval' = '5 min',
          'sink.rolling-policy.check-interval' = '1 min'
        );
        """, sink.getTableName(), columns, partitionClause, path);
}
```

Update the main `generateSql(Pipeline pipeline)` method to dispatch on source/sink type:
- If `source instanceof S3PipelineSource` → call `generateS3SourceDdl`
- If `source instanceof KafkaPipelineSource` → call existing Kafka method
- Same for sinks

### 7. FlinkDeploymentBuilder — S3 Credential Injection

In `FlinkDeploymentBuilder`, after building the base deployment, check if any sources/sinks use S3 + ACCESS_KEY auth:

```java
// Collect unique S3 credentials (assume all ACCESS_KEY sources/sinks use same credentials)
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

### 8. PipelineService — Mapper Updates

Update the mapper that converts `PipelineSourceRequest` → `PipelineSource` entity to handle both types:
- `KafkaSourceRequest` → `KafkaPipelineSource`
- `S3SourceRequest` → `S3PipelineSource`

Remove the Confluent Schema Registry validation call for S3 sources (no schema registry involved).

### 9. Frontend — API Types (frontend/src/api/pipelines.ts)

Add new types:

```typescript
export type ConnectorType = 'KAFKA' | 'S3';
export type S3AuthType = 'IAM_ROLE' | 'ACCESS_KEY';

export interface ColumnDefinition {
  name: string;
  type: string;
}

export interface KafkaSource {
  type: 'KAFKA';
  tableName: string;
  topic: string;
  bootstrapServers: string;
  consumerGroup: string;
  startupMode: StartupMode;
  schemaRegistryUrl: string;
  avroSubject: string;
  watermarkColumn?: string;
  watermarkDelayMs?: number;
}

export interface S3Source {
  type: 'S3';
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey?: string;
  secretKey?: string;
  columns: ColumnDefinition[];
}

export type PipelineSourceConfig = KafkaSource | S3Source;

// Similarly for sinks: KafkaSink, S3Sink, PipelineSinkConfig
```

Update `Pipeline` and `CreatePipelineRequest` to use `PipelineSourceConfig[]` and `PipelineSinkConfig[]` instead of the current source/sink types.

### 10. Frontend — Pipeline Creation Form (PipelineEditorPage.tsx)

**Step 1 (Sources)** and **Step 2 (Sinks)**: Add a source/sink type toggle at the top of each source/sink card.

For each source card:
- Radio group: `● Kafka (Avro)  ○ S3 (Parquet)` — switching type resets all type-specific fields
- When KAFKA: render existing fields (topic, bootstrapServers, consumerGroup, startupMode, schemaRegistryUrl, avroSubject, watermark fields)
- When S3: render S3-specific fields:
  - Table Name (same field, always shown)
  - S3 Bucket (text input)
  - S3 Prefix (text input)
  - Hive-style partitioned paths (checkbox)
  - Auth: `● IAM Role (no credentials)  ○ Access Key + Secret`
    - When Access Key selected: reveal Access Key ID + Secret Access Key text inputs
  - Columns editor: table with "Column Name" and "SQL Type" columns; "+ Add Column" button below; SQL Type dropdown with: STRING, BIGINT, INT, DOUBLE, FLOAT, BOOLEAN, TIMESTAMP(3), DATE

S3 sink has the same fields plus:
- Partition output checkbox — when checked, reveals "Partition Columns" multi-select (from the defined columns list)
- Informational note: "Files roll every 5 min or 128 MB — no configuration required."

**Validation**: Before proceeding to next step, validate:
- S3: bucket and prefix are non-empty
- S3: at least one column is defined
- S3 + ACCESS_KEY: access key and secret key are non-empty

### 11. Frontend — Pipeline Detail Page (PipelineDetailPage.tsx)

Update source/sink display section to handle both types:

- Kafka source card: current display (topic, bootstrapServers, consumer group, avro subject, startup mode)
- S3 source card:
  - Badge: `[S3]`
  - Table name
  - Bucket + prefix
  - Auth type (IAM Role or Access Key — never show the actual key value)
  - Partitioned: Yes/No
  - Columns: comma-separated list of `name TYPE`
- S3 sink card: same pattern + "Roll: 5 min / 128 MB"

Detect source/sink type from the `type` field returned in the API response.

## Success Criteria
- [ ] `POST /api/v1/pipelines` with `sources[0].type = "S3"` returns 201 and stores bucket, prefix, columns in DB
- [ ] `POST /api/v1/pipelines` with `sinks[0].type = "S3"` returns 201 and stores S3 sink fields in DB
- [ ] `GET /api/v1/pipelines/{id}` returns `sourceType: S3` with all S3 fields (bucket, prefix, authType, columns)
- [ ] FlinkSqlGenerator produces `CREATE TABLE ... WITH ('connector'='filesystem', 'format'='parquet')` for S3 sources and sinks
- [ ] FlinkDeploymentBuilder adds `s3.access-key` / `s3.secret-key` to flinkConfiguration when ACCESS_KEY auth is present
- [ ] Pipeline creation form renders S3 source/sink fields when S3 type is selected
- [ ] Pipeline detail page shows S3 source/sink config without exposing secret key value
- [ ] All existing Kafka pipeline tests pass without regression
- [ ] `pom.xml` includes `flink-parquet` and `flink-s3-fs-hadoop` dependencies

## Risks
- **JPA inheritance refactoring breaks existing Kafka pipelines**: Migrating from concrete `PipelineSource` to abstract parent + `KafkaPipelineSource` subclass requires careful entity mapping. Mitigation: Run full integration test suite after entity changes. The discriminator default `'KAFKA'` ensures all existing rows are treated as `KafkaPipelineSource`.
- **S3 credential plaintext**: Access keys stored plaintext in DB. Mitigation: Note is acceptable for MVP; add TODO comment and document in PR for future encryption.
- **React form state complexity**: Switching source type must reset all type-specific fields without losing the table name. Mitigation: Keep type-independent fields (tableName) in shared state and type-specific fields in nested state objects.
- **FlinkDeploymentBuilder credential scope**: If a pipeline has two S3 sources with different access keys, only one can be injected. Mitigation: Validate at API level that all S3 ACCESS_KEY sources/sinks in a pipeline use the same credentials (or use IAM Role for multi-credential scenarios).

## Boundaries
This unit does NOT handle:
- S3 schema inference from Parquet file headers (MVP requires user-supplied columns)
- Encryption of S3 credentials at rest
- S3-to-S3 pipeline validation (platform supports any combination of Kafka and S3 sources/sinks)
- Configurable rolling policies (fixed at 5 min / 128 MB)
- Avro format for S3 (only Parquet is supported)

## Notes
- The `source_type` column defaults to `'KAFKA'` in the migration — all existing pipeline_sources rows will be treated as `KafkaPipelineSource` automatically
- Use `s3a://` URI scheme (Hadoop-based), not `s3://` or `s3p://`
- S3 partitioned sources: the `partitioned` flag is a UI hint; the platform generates the same `CREATE TABLE` DDL regardless (Flink auto-discovers `key=value` directories). If partitioned is true, add `PARTITIONED BY (...)` using the column names that correspond to partition keys — builder must decide which columns are partition columns based on common convention (last N columns, or user designates partition columns separately in a future iteration). For MVP: when `partitioned=true`, builder adds a note in the SQL comment but does not add `PARTITIONED BY` automatically (avoids column-ordering bugs). Users can add it manually in the SQL query step.
- Test file to create: `FlinkSqlGeneratorS3Test.java` with unit tests for S3 source DDL, S3 sink DDL, partitioned source, and rolling policy constants
