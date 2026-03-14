package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@DiscriminatorValue("KAFKA")
@Getter
@Setter
@NoArgsConstructor
public class KafkaPipelineSource extends PipelineSource {

    @Column(name = "topic")
    private String topic;

    @Column(name = "bootstrap_servers", columnDefinition = "TEXT")
    private String bootstrapServers;

    @Column(name = "consumer_group")
    private String consumerGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "startup_mode", length = 20)
    private StartupMode startupMode = StartupMode.GROUP_OFFSETS;

    @Column(name = "schema_registry_url", columnDefinition = "TEXT")
    private String schemaRegistryUrl;

    @Column(name = "avro_subject")
    private String avroSubject;

    @Column(name = "watermark_column")
    private String watermarkColumn;

    @Column(name = "watermark_delay_ms")
    private Long watermarkDelayMs;

    @Column(name = "extra_properties", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String extraProperties = "{}";

    @Column(name = "columns", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ColumnDefinition> columns = new ArrayList<>();
}
