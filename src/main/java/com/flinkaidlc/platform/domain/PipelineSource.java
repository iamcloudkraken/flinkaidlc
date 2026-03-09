package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "pipeline_sources")
@Getter
@Setter
@NoArgsConstructor
public class PipelineSource {

    @Id
    @UuidGenerator
    @Column(name = "source_id", updatable = false, nullable = false)
    private UUID sourceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "bootstrap_servers", nullable = false, columnDefinition = "TEXT")
    private String bootstrapServers;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "startup_mode", nullable = false, length = 20)
    private StartupMode startupMode = StartupMode.GROUP_OFFSETS;

    @Column(name = "schema_registry_url", nullable = false, columnDefinition = "TEXT")
    private String schemaRegistryUrl;

    @Column(name = "avro_subject", nullable = false)
    private String avroSubject;

    @Column(name = "watermark_column")
    private String watermarkColumn;

    @Column(name = "watermark_delay_ms")
    private Long watermarkDelayMs;

    @Column(name = "extra_properties", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String extraProperties = "{}";
}
