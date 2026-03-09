package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineSink;
import com.flinkaidlc.platform.domain.PipelineSource;
import org.springframework.stereotype.Component;

/**
 * Generates Flink SQL {@code statements.sql} content from a {@link Pipeline} domain object.
 *
 * <p>The generated file contains:
 * <ol>
 *   <li>One {@code CREATE TABLE} DDL per source (with Kafka connector + Avro Confluent format)</li>
 *   <li>One {@code CREATE TABLE} DDL per sink</li>
 *   <li>The client-supplied {@code sqlQuery} (passed through verbatim)</li>
 * </ol>
 *
 * <p><b>Schema columns (v1):</b> A flexible {@code BYTES}-typed data column is used.
 * Full Avro schema inference from Schema Registry (generating typed DDL columns) is a v2
 * enhancement and is explicitly out of scope for this unit.
 */
@Component
public class FlinkSqlGenerator {

    /**
     * Generates the complete {@code statements.sql} content for a pipeline.
     *
     * @param pipeline the pipeline domain object (with populated sources and sinks)
     * @return full SQL string to be stored in the ConfigMap
     */
    public String generate(Pipeline pipeline) {
        StringBuilder sb = new StringBuilder();

        // Source table DDLs
        for (PipelineSource source : pipeline.getSources()) {
            sb.append(generateSourceDdl(source)).append("\n\n");
        }

        // Sink table DDLs
        for (PipelineSink sink : pipeline.getSinks()) {
            sb.append(generateSinkDdl(sink)).append("\n\n");
        }

        // Client SQL
        sb.append(pipeline.getSqlQuery()).append("\n");

        return sb.toString();
    }

    private String generateSourceDdl(PipelineSource source) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(escape(source.getTableName())).append(" (\n");
        sb.append("  `data` BYTES");

        // Add watermark clause if watermark column is specified
        if (source.getWatermarkColumn() != null && !source.getWatermarkColumn().isBlank()) {
            long delayMs = source.getWatermarkDelayMs() != null ? source.getWatermarkDelayMs() : 5000L;
            sb.append(",\n  `").append(source.getWatermarkColumn()).append("` TIMESTAMP(3)");
            sb.append(",\n  WATERMARK FOR `").append(source.getWatermarkColumn())
              .append("` AS `").append(source.getWatermarkColumn())
              .append("` - INTERVAL '").append(delayMs).append("' MILLISECOND");
        }

        sb.append("\n) WITH (\n");
        sb.append("  'connector' = 'kafka',\n");
        sb.append("  'topic' = '").append(escape(source.getTopic())).append("',\n");
        sb.append("  'properties.bootstrap.servers' = '").append(escape(source.getBootstrapServers())).append("',\n");
        sb.append("  'properties.group.id' = '").append(escape(source.getConsumerGroup())).append("',\n");
        sb.append("  'scan.startup.mode' = '").append(startupModeValue(source.getStartupMode().name())).append("',\n");
        sb.append("  'format' = 'avro-confluent',\n");
        sb.append("  'avro-confluent.url' = '").append(escape(source.getSchemaRegistryUrl())).append("',\n");
        sb.append("  'avro-confluent.subject' = '").append(escape(source.getAvroSubject())).append("'\n");
        sb.append(");");
        return sb.toString();
    }

    private String generateSinkDdl(PipelineSink sink) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(escape(sink.getTableName())).append(" (\n");
        sb.append("  `data` BYTES\n");
        sb.append(") WITH (\n");
        sb.append("  'connector' = 'kafka',\n");
        sb.append("  'topic' = '").append(escape(sink.getTopic())).append("',\n");
        sb.append("  'properties.bootstrap.servers' = '").append(escape(sink.getBootstrapServers())).append("',\n");
        sb.append("  'format' = 'avro-confluent',\n");
        sb.append("  'avro-confluent.url' = '").append(escape(sink.getSchemaRegistryUrl())).append("',\n");
        sb.append("  'avro-confluent.subject' = '").append(escape(sink.getAvroSubject())).append("',\n");
        sb.append("  'sink.delivery-guarantee' = '").append(deliveryGuaranteeValue(sink.getDeliveryGuarantee().name())).append("'\n");
        sb.append(");");
        return sb.toString();
    }

    /**
     * Escapes single quotes in SQL string literals to prevent injection into generated SQL.
     * Values are configuration strings from user input stored in domain objects; they must
     * not break out of the single-quoted WITH clause values.
     */
    static String escape(String value) {
        if (value == null) return "";
        return value.replace("'", "\\'");
    }

    private String startupModeValue(String enumName) {
        return switch (enumName) {
            case "GROUP_OFFSETS" -> "group-offsets";
            case "EARLIEST" -> "earliest-offset";
            case "LATEST" -> "latest-offset";
            default -> "group-offsets";
        };
    }

    private String deliveryGuaranteeValue(String enumName) {
        return switch (enumName) {
            case "EXACTLY_ONCE" -> "exactly-once";
            case "AT_LEAST_ONCE" -> "at-least-once";
            case "NONE" -> "none";
            default -> "at-least-once";
        };
    }
}
