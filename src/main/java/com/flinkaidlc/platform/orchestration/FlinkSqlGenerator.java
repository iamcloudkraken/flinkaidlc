package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates Flink SQL {@code statements.sql} content from a {@link Pipeline} domain object.
 *
 * <p>The generated file contains:
 * <ol>
 *   <li>One {@code CREATE TABLE} DDL per source (Kafka or S3/filesystem)</li>
 *   <li>One {@code CREATE TABLE} DDL per sink</li>
 *   <li>The client-supplied {@code sqlQuery} (passed through verbatim)</li>
 * </ol>
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
            if (source instanceof KafkaPipelineSource kafka) {
                sb.append(generateKafkaSourceDdl(kafka)).append("\n\n");
            } else if (source instanceof S3PipelineSource s3) {
                sb.append(generateS3SourceDdl(s3)).append("\n\n");
            }
        }

        // Sink table DDLs
        for (PipelineSink sink : pipeline.getSinks()) {
            if (sink instanceof KafkaPipelineSink kafka) {
                sb.append(generateKafkaSinkDdl(kafka)).append("\n\n");
            } else if (sink instanceof S3PipelineSink s3) {
                sb.append(generateS3SinkDdl(s3)).append("\n\n");
            }
        }

        // Client SQL
        sb.append(pipeline.getSqlQuery()).append("\n");

        return sb.toString();
    }

    private String generateKafkaSourceDdl(KafkaPipelineSource source) {
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

    private String generateKafkaSinkDdl(KafkaPipelineSink sink) {
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

    private String generateS3SourceDdl(S3PipelineSource source) {
        String path = "s3a://" + escape(source.getBucket())
                + (source.getPrefix() != null && !source.getPrefix().isBlank()
                   ? "/" + escape(source.getPrefix())
                   : "");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(escape(source.getTableName())).append(" (\n");

        List<ColumnDefinition> columns = source.getColumns();
        if (columns != null && !columns.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                ColumnDefinition col = columns.get(i);
                sb.append("  `").append(escape(col.name())).append("` ").append(col.type());
                if (i < columns.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
        } else {
            sb.append("  `data` BYTES\n");
        }

        sb.append(") WITH (\n");
        sb.append("  'connector' = 'filesystem',\n");
        sb.append("  'path' = '").append(path).append("',\n");
        sb.append("  'format' = 'parquet',\n");
        sb.append("  'source.path.regex-pattern' = '.*\\.parquet$'\n");
        sb.append(");");
        return sb.toString();
    }

    private String generateS3SinkDdl(S3PipelineSink sink) {
        String path = "s3a://" + escape(sink.getBucket())
                + (sink.getPrefix() != null && !sink.getPrefix().isBlank()
                   ? "/" + escape(sink.getPrefix())
                   : "");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(escape(sink.getTableName())).append(" (\n");

        List<ColumnDefinition> columns = sink.getColumns();
        if (columns != null && !columns.isEmpty()) {
            for (int i = 0; i < columns.size(); i++) {
                ColumnDefinition col = columns.get(i);
                sb.append("  `").append(escape(col.name())).append("` ").append(col.type());
                if (i < columns.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
        } else {
            sb.append("  `data` BYTES\n");
        }

        sb.append(")");

        List<String> partitionColumns = sink.getS3PartitionColumns();
        if (partitionColumns != null && !partitionColumns.isEmpty()) {
            sb.append("\nPARTITIONED BY (");
            sb.append(String.join(", ", partitionColumns.stream().map(c -> "`" + escape(c) + "`").toList()));
            sb.append(")");
        }

        sb.append("\nWITH (\n");
        sb.append("  'connector' = 'filesystem',\n");
        sb.append("  'path' = '").append(path).append("',\n");
        sb.append("  'format' = 'parquet',\n");
        sb.append("  'sink.rolling-policy.file-size' = '128MB',\n");
        sb.append("  'sink.rolling-policy.rollover-interval' = '5 min',\n");
        sb.append("  'sink.rolling-policy.check-interval' = '1 min'\n");
        sb.append(");");
        return sb.toString();
    }

    /**
     * Escapes single quotes in SQL string literals to prevent injection into generated SQL.
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
