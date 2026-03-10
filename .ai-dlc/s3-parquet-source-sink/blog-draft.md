# Bringing S3 and Parquet to the Flink SQL Pipeline Platform

## The Gap

When we built the Flink SQL Pipeline Platform, we focused on the most common real-time pattern: Kafka-to-Kafka transformations. Write a SQL query, define your Avro topics, and the platform handles the rest — generating `CREATE TABLE` DDL, wrapping it in a FlinkDeployment CRD, and deploying to Kubernetes via the Flink Operator.

But real data systems don't live exclusively in Kafka. Teams want to:

- Land enriched Kafka data into a data lake (S3) for historical querying
- Read reference data from S3 and join it with a Kafka stream
- Run batch-style pipelines that move data between S3 prefixes in Parquet format

Without native S3 support, all of this required hand-writing Flink SQL, building deployment descriptors manually, and bypassing the platform entirely. That's not self-service.

## What We Built

We extended the platform to treat **S3 (Parquet)** as a first-class connector alongside Kafka (Avro). The change is end-to-end: database schema, REST API, Flink SQL generation, deployment credential injection, and the React UI.

### Single-Table Inheritance

The key architectural decision was how to store both Kafka and S3 sources in the same `pipeline_sources` table. We used **JPA single-table inheritance** with a `source_type` discriminator column:

```sql
ALTER TABLE pipeline_sources
  ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'KAFKA',
  ADD COLUMN s3_bucket VARCHAR(255),
  ADD COLUMN s3_prefix VARCHAR(500),
  -- ...S3-specific columns...
  ADD COLUMN columns JSONB DEFAULT '[]';
```

The `DEFAULT 'KAFKA'` means all existing rows automatically become `KafkaPipelineSource` objects. No data migration needed.

On the Java side:

```java
@Entity
@Table(name = "pipeline_sources")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "source_type")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sourceType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = KafkaPipelineSource.class, name = "KAFKA"),
    @JsonSubTypes.Type(value = S3PipelineSource.class, name = "S3")
})
public abstract class PipelineSource { ... }
```

The `@JsonTypeInfo` annotation handles both deserialization (API → entity) and serialization (entity → API response) automatically. The `sourceType` field appears in `GET /api/v1/pipelines/{id}` responses, so the frontend and API consumers can distinguish source types.

### Flink SQL Generation

For Kafka sources, we generate the `kafka` connector DDL with Avro schema. For S3 sources:

```java
public String generateS3SourceDdl(S3PipelineSource source) {
    String path = "s3a://" + source.getBucket() + "/" + source.getPrefix();
    String columns = source.getColumns().stream()
        .map(c -> "`" + c.name() + "` " + c.type())
        .collect(joining(",\n  "));

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
```

Note `s3a://` — not `s3://`. Flink's filesystem connector uses the Hadoop S3A implementation. Using the wrong scheme is a common gotcha.

S3 sinks get a fixed rolling policy to prevent tiny files:

```
'sink.rolling-policy.file-size' = '128MB'
'sink.rolling-policy.rollover-interval' = '5 min'
'sink.rolling-policy.check-interval' = '1 min'
```

### Credential Injection

S3 credentials can't be passed as `WITH` properties in Flink SQL — they must be in the Flink deployment's `flinkConfiguration`. The `FlinkDeploymentBuilder` handles this:

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
```

If the pipeline uses IAM Role auth, nothing is injected — the Flink pod assumes the role automatically.

### The UI

The pipeline creation form now shows a connector type toggle on each source and sink card. Switching from Kafka to S3 resets type-specific fields but preserves the table name. The S3 form includes an inline column editor where users define each column name and SQL type.

The pipeline detail page shows S3 cards with bucket, prefix, auth mode, and column list. The secret key is never returned by the API or displayed in the UI.

## The Result

A team can now create a Kafka-to-S3 pipeline in 3 minutes through the UI:

1. Define a Kafka source (topic + schema)
2. Define an S3 sink (bucket + prefix + IAM auth + output columns)
3. Write the SQL transform
4. Deploy

The platform generates all the Flink SQL DDL, builds the FlinkDeployment CRD, and handles the full lifecycle. No Kubernetes YAML, no JAR builds, no infrastructure knowledge required.

## What's Next

- Encryption at rest for S3 access keys stored in the database
- S3 schema inference from Parquet file headers (so users don't need to manually define columns)
- Configurable rolling policies for power users
- Avro format for S3 (currently only Parquet)
