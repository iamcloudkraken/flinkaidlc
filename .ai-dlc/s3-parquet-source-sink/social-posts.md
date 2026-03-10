# Social Posts — S3 (Parquet) Source and Sink Support

## Twitter / X (280 chars)

> S3 (Parquet) sources and sinks are now live in the Flink SQL Pipeline Platform. Point at a bucket, define your columns, pick IAM or access key auth — the platform handles the Flink SQL DDL and credential injection automatically. No more hand-written FlinkDeployment YAML.

---

> New: read from and write to S3 Parquet directly from our pipeline creation UI. Choose Kafka (Avro) or S3 (Parquet) per source/sink — they work together in the same pipeline. Rolling sink policy fixed at 5 min / 128 MB. Fully self-service.

---

## LinkedIn

We just shipped **S3 (Parquet) source and sink support** for the Flink SQL Pipeline Platform.

Before this: if you wanted to read from S3 or land data in a data lake, you had to write Flink SQL by hand, build the JAR yourself, and craft a FlinkDeployment CRD manually. Not exactly self-service.

Now:
✅ Select "S3 (Parquet)" on the source or sink card in the pipeline creation form
✅ Enter your bucket, prefix, and column definitions
✅ Choose IAM Role or Access Key auth
✅ Platform generates the `CREATE TABLE ... WITH ('connector'='filesystem', 'format'='parquet')` DDL and injects credentials into the Flink deployment at runtime

Kafka and S3 connectors can be mixed in a single pipeline — e.g. Kafka source → S3 sink for a streaming-to-data-lake pattern.

The API is fully backward compatible. Existing Kafka pipelines require zero changes.

#ApacheFlink #DataEngineering #DataLake #Parquet #S3 #StreamProcessing
