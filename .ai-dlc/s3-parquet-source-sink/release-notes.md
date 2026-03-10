# Release Notes — S3 (Parquet) Source and Sink Support

## What's New

You can now read from and write to **Amazon S3 in Parquet format** directly from the Flink SQL Pipeline Platform — no hand-written SQL required.

When creating a pipeline, each source and sink independently lets you choose between:

- **Kafka (Avro)** — the existing connector, unchanged
- **S3 (Parquet)** — new: reads from or writes to S3 object storage in Apache Parquet format

### Creating an S3 Source

Select **S3 (Parquet)** on the Sources step, then provide:

| Field | Description |
|-------|-------------|
| S3 Bucket | The bucket name (e.g. `my-data-lake`) |
| S3 Prefix | The path prefix (e.g. `events/raw/`) |
| Auth | IAM Role (no credentials) or Access Key + Secret |
| Columns | Define each column name and SQL type (STRING, BIGINT, TIMESTAMP(3), etc.) |

### Creating an S3 Sink

Select **S3 (Parquet)** on the Sinks step. In addition to the source fields above:

| Field | Description |
|-------|-------------|
| Partition Output | Optional: partition output files by one or more columns (Hive-style directories) |

Output files roll automatically every **5 minutes or 128 MB** — no configuration needed.

### Authentication

Two auth modes are supported:

- **IAM Role** — The Flink pod assumes an IAM role. No credentials are stored.
- **Access Key** — An AWS access key ID and secret access key are provided at pipeline creation and injected into the Flink deployment at runtime. The secret key is never returned by the API.

### Compatibility

All existing Kafka pipelines continue to work without any changes. The API is backward compatible — existing `POST /api/v1/pipelines` requests without a `"type"` field default to Kafka.
