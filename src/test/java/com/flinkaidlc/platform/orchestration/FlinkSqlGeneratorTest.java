package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlinkSqlGeneratorTest {

    private FlinkSqlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new FlinkSqlGenerator();
    }

    @Test
    void generate_withSourceAndSink_containsAllSections() {
        Pipeline pipeline = buildPipeline("INSERT INTO output SELECT * FROM input");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("CREATE TABLE input");
        assertThat(sql).contains("CREATE TABLE output");
        assertThat(sql).contains("INSERT INTO output SELECT * FROM input");
        assertThat(sql).contains("'connector' = 'kafka'");
        assertThat(sql).contains("'format' = 'avro-confluent'");
    }

    @Test
    void generate_withWatermark_includesWatermarkClause() {
        Pipeline pipeline = buildPipeline("SELECT * FROM input");
        pipeline.getSources().get(0).setWatermarkColumn("event_time");
        pipeline.getSources().get(0).setWatermarkDelayMs(5000L);

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("WATERMARK FOR `event_time`");
        assertThat(sql).contains("INTERVAL '5000' MILLISECOND");
    }

    @Test
    void generate_withoutWatermark_noWatermarkClause() {
        Pipeline pipeline = buildPipeline("SELECT * FROM input");
        pipeline.getSources().get(0).setWatermarkColumn(null);

        String sql = generator.generate(pipeline);

        assertThat(sql).doesNotContain("WATERMARK");
    }

    @Test
    void generate_multipleSourcesAndSinks() {
        Pipeline pipeline = buildPipeline("INSERT INTO sink1 SELECT * FROM src1");
        pipeline.getSources().get(0).setTableName("src1");
        pipeline.getSinks().get(0).setTableName("sink1");

        PipelineSource src2 = new PipelineSource();
        src2.setTableName("src2");
        src2.setTopic("t2");
        src2.setBootstrapServers("kafka:9092");
        src2.setConsumerGroup("cg2");
        src2.setStartupMode(StartupMode.EARLIEST);
        src2.setSchemaRegistryUrl("http://sr:8081");
        src2.setAvroSubject("src2-value");
        pipeline.addSource(src2);

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("CREATE TABLE src1");
        assertThat(sql).contains("CREATE TABLE src2");
        assertThat(sql).contains("'scan.startup.mode' = 'earliest-offset'");
    }

    @Test
    void generate_singleQuoteInValue_isEscaped() {
        Pipeline pipeline = buildPipeline("SELECT * FROM input");
        pipeline.getSources().get(0).setTopic("my'topic");

        String sql = generator.generate(pipeline);

        assertThat(sql).contains("my\\'topic");
        assertThat(sql).doesNotContain("my'topic");
    }

    @Test
    void escape_nullValue_returnsEmpty() {
        assertThat(FlinkSqlGenerator.escape(null)).isEmpty();
    }

    @Test
    void escape_singleQuote_isEscaped() {
        assertThat(FlinkSqlGenerator.escape("it's a test")).isEqualTo("it\\'s a test");
    }

    private Pipeline buildPipeline(String sqlQuery) {
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(UUID.randomUUID());
        pipeline.setSqlQuery(sqlQuery);
        pipeline.setName("Test Pipeline");
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);

        PipelineSource source = new PipelineSource();
        source.setTableName("input");
        source.setTopic("t1");
        source.setBootstrapServers("kafka:9092");
        source.setConsumerGroup("cg1");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://sr:8081");
        source.setAvroSubject("input-value");
        pipeline.addSource(source);

        PipelineSink sink = new PipelineSink();
        sink.setTableName("output");
        sink.setTopic("t-out");
        sink.setBootstrapServers("kafka:9092");
        sink.setSchemaRegistryUrl("http://sr:8081");
        sink.setAvroSubject("output-value");
        sink.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        pipeline.addSink(sink);

        return pipeline;
    }
}
