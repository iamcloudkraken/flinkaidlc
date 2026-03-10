package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlinkSqlGeneratorS3Test {

    private FlinkSqlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new FlinkSqlGenerator();
    }

    @Test
    void generate_s3Source_producesFilesystemConnectorDdl() {
        Pipeline pipeline = buildPipelineWithS3Source("INSERT INTO s3_output SELECT id, name FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("connector' = 'filesystem'");
        assertThat(sql).contains("format' = 'parquet'");
        assertThat(sql).contains("s3a://my-bucket/data/events");
        assertThat(sql).contains("`id` BIGINT");
        assertThat(sql).contains("`name` STRING");
    }

    @Test
    void generate_s3Source_tableNameIsUsed() {
        Pipeline pipeline = buildPipelineWithS3Source("SELECT id FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("CREATE TABLE s3_input");
    }

    @Test
    void generate_s3Sink_includesRollingPolicy() {
        Pipeline pipeline = buildPipelineWithS3Sink("INSERT INTO s3_output SELECT id, name FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("sink.rolling-policy.file-size' = '128MB'");
        assertThat(sql).contains("sink.rolling-policy.rollover-interval' = '5 min'");
        assertThat(sql).contains("connector' = 'filesystem'");
        assertThat(sql).contains("format' = 'parquet'");
    }

    @Test
    void generate_s3SinkWithPartitionColumns_includesPartitionedByClause() {
        Pipeline pipeline = buildPipelineWithPartitionedS3Sink("INSERT INTO s3_output SELECT id, name FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("PARTITIONED BY");
        assertThat(sql).contains("`name`");
    }

    @Test
    void generate_s3SinkWithoutPartitionColumns_noPartitionedByClause() {
        Pipeline pipeline = buildPipelineWithS3Sink("INSERT INTO s3_output SELECT id, name FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).doesNotContain("PARTITIONED BY");
    }

    @Test
    void generate_s3SourceWithoutColumns_fallsBackToDataBytes() {
        S3PipelineSource source = new S3PipelineSource();
        source.setTableName("s3_input");
        source.setBucket("my-bucket");
        source.setPrefix("data/events");
        source.setAuthType(S3AuthType.IAM_ROLE);
        source.setColumns(List.of());

        S3PipelineSink sink = new S3PipelineSink();
        sink.setTableName("s3_output");
        sink.setBucket("my-bucket");
        sink.setPrefix("data/output");
        sink.setAuthType(S3AuthType.IAM_ROLE);
        sink.setColumns(List.of());
        sink.setS3PartitionColumns(List.of());

        Pipeline pipeline = buildPipeline(source, sink, "INSERT INTO s3_output SELECT * FROM s3_input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("`data` BYTES");
    }

    // ---- helpers ----

    private Pipeline buildPipelineWithS3Source(String sqlQuery) {
        S3PipelineSource source = new S3PipelineSource();
        source.setTableName("s3_input");
        source.setBucket("my-bucket");
        source.setPrefix("data/events");
        source.setAuthType(S3AuthType.IAM_ROLE);
        source.setColumns(List.of(
            new ColumnDefinition("id", "BIGINT"),
            new ColumnDefinition("name", "STRING")
        ));

        S3PipelineSink sink = new S3PipelineSink();
        sink.setTableName("s3_output");
        sink.setBucket("my-bucket");
        sink.setPrefix("data/output");
        sink.setAuthType(S3AuthType.IAM_ROLE);
        sink.setColumns(List.of(
            new ColumnDefinition("id", "BIGINT"),
            new ColumnDefinition("name", "STRING")
        ));
        sink.setS3PartitionColumns(List.of());

        return buildPipeline(source, sink, sqlQuery);
    }

    private Pipeline buildPipelineWithS3Sink(String sqlQuery) {
        KafkaPipelineSource source = new KafkaPipelineSource();
        source.setTableName("s3_input");
        source.setTopic("t1");
        source.setBootstrapServers("kafka:9092");
        source.setConsumerGroup("cg1");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://sr:8081");
        source.setAvroSubject("input-value");

        S3PipelineSink sink = new S3PipelineSink();
        sink.setTableName("s3_output");
        sink.setBucket("output-bucket");
        sink.setPrefix("data/output");
        sink.setAuthType(S3AuthType.IAM_ROLE);
        sink.setColumns(List.of(
            new ColumnDefinition("id", "BIGINT"),
            new ColumnDefinition("name", "STRING")
        ));
        sink.setS3PartitionColumns(List.of());

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setSqlQuery(sqlQuery);
        pipeline.setName("S3 Sink Pipeline");
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.addSource(source);
        pipeline.addSink(sink);
        return pipeline;
    }

    private Pipeline buildPipelineWithPartitionedS3Sink(String sqlQuery) {
        KafkaPipelineSource source = new KafkaPipelineSource();
        source.setTableName("s3_input");
        source.setTopic("t1");
        source.setBootstrapServers("kafka:9092");
        source.setConsumerGroup("cg1");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://sr:8081");
        source.setAvroSubject("input-value");

        S3PipelineSink sink = new S3PipelineSink();
        sink.setTableName("s3_output");
        sink.setBucket("output-bucket");
        sink.setPrefix("data/output");
        sink.setAuthType(S3AuthType.IAM_ROLE);
        sink.setColumns(List.of(
            new ColumnDefinition("id", "BIGINT"),
            new ColumnDefinition("name", "STRING")
        ));
        sink.setS3PartitionColumns(List.of("name"));

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setSqlQuery(sqlQuery);
        pipeline.setName("Partitioned S3 Sink Pipeline");
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.addSource(source);
        pipeline.addSink(sink);
        return pipeline;
    }

    private Pipeline buildPipeline(PipelineSource source, PipelineSink sink, String sqlQuery) {
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setSqlQuery(sqlQuery);
        pipeline.setName("S3 Pipeline");
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.addSource(source);
        pipeline.addSink(sink);
        return pipeline;
    }
}
