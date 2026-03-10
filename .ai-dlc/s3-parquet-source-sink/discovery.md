---
intent: s3-parquet-source-sink
created: 2026-03-10
status: active
---

# Discovery Log: S3 (Parquet) Source and Sink Support

Elaboration findings persisted during Phase 2.5 domain discovery.
Builders: read section headers for an overview, then dive into specific sections as needed.

---

## Codebase Pattern: Existing Pipeline Domain Model

### Entities

**Pipeline** (table: `pipelines`)
- pipelineId, tenantId, name, description, sqlQuery
- status: PipelineStatus (DRAFT/DEPLOYING/RUNNING/SUSPENDED/FAILED/DELETED)
- parallelism (1–256), checkpointIntervalMs (min 1000)
- upgradeMode: UpgradeMode (SAVEPOINT/LAST_STATE/STATELESS)
- sources: List<PipelineSource> (one-to-many, cascade delete)
- sinks: List<PipelineSink> (one-to-many, cascade delete)
- deployment: PipelineDeployment (one-to-one)

**PipelineSource** (table: `pipeline_sources`) — KAFKA ONLY TODAY
- sourceId, pipeline (FK), tableName
- topic, bootstrapServers, consumerGroup
- startupMode: StartupMode (GROUP_OFFSETS/EARLIEST/LATEST)
- schemaRegistryUrl, avroSubject
- watermarkColumn (optional), watermarkDelayMs (optional)
- extraProperties: JSONB (default '{}')

**PipelineSink** (table: `pipeline_sinks`) — KAFKA ONLY TODAY
- sinkId, pipeline (FK), tableName
- topic, bootstrapServers, schemaRegistryUrl, avroSubject
- partitioner: Partitioner (DEFAULT/FIXED/ROUND_ROBIN)
- deliveryGuarantee: DeliveryGuarantee (AT_LEAST_ONCE/EXACTLY_ONCE)

**PipelineDeployment** — holds K8s/Flink runtime state

### Key Services

- **PipelineService**: creates, updates, suspends, resumes, deletes pipelines; validates SQL + schema registry; calls `orchestrationService`
- **FlinkSqlGenerator**: converts PipelineSource/PipelineSink to Flink SQL `CREATE TABLE` DDL
- **FlinkDeploymentBuilder**: builds the Flink K8s CRD (FlinkDeployment), configures checkpointing S3 bucket, pod specs
- **PipelineController**: REST endpoints under `/api/v1/pipelines`

---

## Codebase Pattern: FlinkSqlGenerator (Kafka SQL Generation)

Current generated SQL:

```sql
-- Source
CREATE TABLE {tableName} (
  `data` BYTES
  [, `{watermarkColumn}` TIMESTAMP(3),
     WATERMARK FOR `{watermarkColumn}` AS `{watermarkColumn}` - INTERVAL '{delayMs}' MILLISECOND]
) WITH (
  'connector' = 'kafka',
  'topic' = '{topic}',
  'properties.bootstrap.servers' = '{bootstrapServers}',
  'properties.group.id' = '{consumerGroup}',
  'scan.startup.mode' = '{startupMode}',   -- group-offsets / earliest-offset / latest-offset
  'format' = 'avro-confluent',
  'avro-confluent.url' = '{schemaRegistryUrl}',
  'avro-confluent.subject' = '{avroSubject}'
);

-- Sink
CREATE TABLE {tableName} (
  `data` BYTES
) WITH (
  'connector' = 'kafka',
  'topic' = '{topic}',
  'properties.bootstrap.servers' = '{bootstrapServers}',
  'format' = 'avro-confluent',
  'avro-confluent.url' = '{schemaRegistryUrl}',
  'avro-confluent.subject' = '{avroSubject}',
  'sink.delivery-guarantee' = '{deliveryGuarantee}'  -- exactly-once / at-least-once
);
```

S3 sources/sinks need analogous generation added to `FlinkSqlGenerator`.

---

## External Research: Flink S3 + Parquet Connector

### Maven Dependencies (Flink 1.18+)

```xml
<!-- Parquet format support -->
<dependency>
  <groupId>org.apache.flink</groupId>
  <artifactId>flink-parquet</artifactId>
  <version>1.18.1</version>
</dependency>

<!-- S3 filesystem (Hadoop-based, uses s3a:// scheme — recommended) -->
<dependency>
  <groupId>org.apache.flink</groupId>
  <artifactId>flink-s3-fs-hadoop</artifactId>
  <version>1.18.1</version>
</dependency>
```

Note: Neither is currently in pom.xml. Both must be added.

### S3 Source CREATE TABLE (flat prefix)

```sql
CREATE TABLE my_s3_source (
  col1 STRING,
  col2 BIGINT,
  col3 TIMESTAMP(3)
) WITH (
  'connector' = 'filesystem',
  'path' = 's3a://my-bucket/data/prefix/',
  'format' = 'parquet',
  'source.path.regex-pattern' = '.*\.parquet$'
);
```

### S3 Source CREATE TABLE (Hive-style partitioned)

```sql
CREATE TABLE my_s3_partitioned_source (
  col1 STRING,
  col2 BIGINT,
  year INT,
  month INT,
  day INT
) PARTITIONED BY (year, month, day) WITH (
  'connector' = 'filesystem',
  'path' = 's3a://my-bucket/data/prefix/',
  'format' = 'parquet'
);
```

Flink automatically discovers partition directories matching `key=value` pattern. No Hive metastore required.

### S3 Sink CREATE TABLE (fixed defaults: 5 min / 128 MB)

```sql
CREATE TABLE my_s3_sink (
  col1 STRING,
  col2 BIGINT
) WITH (
  'connector' = 'filesystem',
  'path' = 's3a://my-bucket/output/prefix/',
  'format' = 'parquet',
  'sink.rolling-policy.file-size' = '128MB',
  'sink.rolling-policy.rollover-interval' = '5 min',
  'sink.rolling-policy.check-interval' = '1 min'
);
```

### S3 Auth: CRITICAL CONSTRAINT

**AWS credentials CANNOT be passed as SQL connector properties.** They must be configured at the Flink deployment level:

- **IAM Role / IRSA** (no-credential path): Pod gets credentials automatically from instance metadata. Nothing extra in SQL.
- **Access Key + Secret**: Must be set in the Flink configuration (`s3.access-key` / `s3.secret-key`) injected into the Flink K8s deployment's `flinkConfiguration` map.

This means **FlinkDeploymentBuilder** must be extended to inject S3 credentials when a pipeline has S3 sources/sinks with access key auth.

---

## Architecture Decision: Polymorphic Source/Sink Entities

### Problem
Current `PipelineSource`/`PipelineSink` entities are Kafka-specific. S3 needs entirely different fields.

### Decision: Single-table inheritance with discriminator column

- Add `source_type VARCHAR(20) NOT NULL DEFAULT 'KAFKA'` to `pipeline_sources` and `pipeline_sinks`
- Add all S3-specific columns as nullable (only populated for S3 rows)
- Use JPA `@Inheritance(strategy = SINGLE_TABLE)` + `@DiscriminatorColumn`
- New subclasses: `KafkaPipelineSource extends PipelineSource`, `S3PipelineSource extends PipelineSource`, same for sinks
- API request DTOs: use `type` discriminator field to deserialize to the correct subtype

**S3-specific fields to add to `pipeline_sources`:**
- s3_bucket VARCHAR(255)
- s3_prefix VARCHAR(500)
- s3_partitioned BOOLEAN DEFAULT FALSE
- s3_auth_type VARCHAR(20) (IAM_ROLE or ACCESS_KEY)
- s3_access_key VARCHAR(255) (nullable, only for ACCESS_KEY auth)
- s3_secret_key VARCHAR(500) (nullable, only for ACCESS_KEY auth)
- columns JSONB DEFAULT '[]' (array of {name, type} — used for both S3 source and S3 sink)

**S3-specific fields to add to `pipeline_sinks`:**
- s3_bucket, s3_prefix, s3_partitioned, s3_auth_type, s3_access_key, s3_secret_key, columns (same as above)
- s3_partition_columns JSONB DEFAULT '[]' (list of column names to partition output by)

---

## Architecture Decision: Auth Credential Storage

Store access key + secret key as plaintext in the DB for MVP (same pattern as existing Kafka bootstrap servers). Mark with TODO for encryption at rest. FlinkDeploymentBuilder injects into `flinkConfiguration`:

```yaml
s3.access-key: {accessKey}
s3.secret-key: {secretKey}
```

For IAM role auth: no injection, pod assumes role via IRSA or instance profile.

---

## UI Mockup: Source Step — Kafka Selected (unchanged)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Sources                                                                     │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Source 1                                                         [✕] │   │
│  │  Source Type  ● Kafka (Avro)  ○ S3 (Parquet)                        │   │
│  │  Table Name   [my_kafka_source                                   ]   │   │
│  │  Topic        [orders                                            ]   │   │
│  │  Bootstrap    [kafka:9092                                        ]   │   │
│  │  Consumer Grp [my-consumer-group                                 ]   │   │
│  │  Startup Mode [Group Offsets                              ▼      ]   │   │
│  │  Schema Reg.  [http://schema-registry:8081                       ]   │   │
│  │  Avro Subject [com.example.Order                                 ]   │   │
│  │  Watermark    [                                                  ]   │   │
│  │  WM Delay ms  [5000                                              ]   │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│  [+ Add Source]                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

## UI Mockup: Source Step — S3 Selected

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Sources                                                                     │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Source 1                                                         [✕] │   │
│  │  Source Type  ○ Kafka (Avro)  ● S3 (Parquet)                        │   │
│  │  Table Name   [my_s3_source                                      ]   │   │
│  │  S3 Bucket    [my-data-bucket                                    ]   │   │
│  │  S3 Prefix    [data/orders/                                      ]   │   │
│  │               ☐ Hive-style partitioned paths (year=2024/month=01/)  │   │
│  │                                                                      │   │
│  │  Auth         ● IAM Role (no credentials)  ○ Access Key + Secret    │   │
│  │                                                                      │   │
│  │  Columns      [+ Add Column]                                         │   │
│  │  ┌────────────────────────────────────────────────────────────┐     │   │
│  │  │ Column Name      │ SQL Type          │                  [✕]│     │   │
│  │  │ [user_id       ] │ [STRING     ▼   ] │                     │     │   │
│  │  │ [amount        ] │ [DOUBLE     ▼   ] │                     │     │   │
│  │  │ [event_time    ] │ [TIMESTAMP(3)▼  ] │                     │     │   │
│  │  └────────────────────────────────────────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│  [+ Add Source]                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Interactions
- Type radio toggle: clears all type-specific fields, shows relevant fields only
- Auth: selecting "Access Key + Secret" reveals two additional inputs
- Columns: inline add/remove rows; type dropdown: STRING/BIGINT/INT/DOUBLE/BOOLEAN/TIMESTAMP(3)/DATE

## UI Mockup: Sink Step — S3 Selected

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Sinks                                                                       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Sink 1                                                           [✕] │   │
│  │  Sink Type    ○ Kafka (Avro)  ● S3 (Parquet)                        │   │
│  │  Table Name   [my_s3_sink                                        ]   │   │
│  │  S3 Bucket    [my-output-bucket                                  ]   │   │
│  │  S3 Prefix    [output/orders/                                    ]   │   │
│  │               ☐ Partition output (specify partition columns below)   │   │
│  │                                                                      │   │
│  │  Auth         ● IAM Role (no credentials)  ○ Access Key + Secret    │   │
│  │                                                                      │   │
│  │  ℹ Files roll every 5 min or 128 MB — no configuration required.   │   │
│  │                                                                      │   │
│  │  Columns      [+ Add Column]                                         │   │
│  │  ┌────────────────────────────────────────────────────────────┐     │   │
│  │  │ Column Name      │ SQL Type          │                  [✕]│     │   │
│  │  │ [user_id       ] │ [STRING     ▼   ] │                     │     │   │
│  │  │ [total         ] │ [DOUBLE     ▼   ] │                     │     │   │
│  │  └────────────────────────────────────────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

## UI Mockup: Pipeline Detail — S3 Source/Sink Display

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Sources                                                                      │
│ ┌────────────────────────────────────────────────────────────────────────┐  │
│ │ [S3]  my_s3_source                                                     │  │
│ │ Bucket: my-data-bucket   Prefix: data/orders/   Partitioned: Yes       │  │
│ │ Auth: IAM Role                                                          │  │
│ │ Columns: user_id STRING, amount DOUBLE, event_time TIMESTAMP(3)        │  │
│ └────────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│ Sinks                                                                        │
│ ┌────────────────────────────────────────────────────────────────────────┐  │
│ │ [S3]  my_s3_sink                                                       │  │
│ │ Bucket: my-output-bucket   Prefix: output/orders/   Roll: 5 min/128MB  │  │
│ │ Auth: IAM Role                                                          │  │
│ │ Columns: user_id STRING, total DOUBLE                                  │  │
│ └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```
