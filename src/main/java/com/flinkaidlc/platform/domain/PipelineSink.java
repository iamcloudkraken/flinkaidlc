package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "pipeline_sinks")
@Getter
@Setter
@NoArgsConstructor
public class PipelineSink {

    @Id
    @UuidGenerator
    @Column(name = "sink_id", updatable = false, nullable = false)
    private UUID sinkId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "bootstrap_servers", nullable = false, columnDefinition = "TEXT")
    private String bootstrapServers;

    @Column(name = "schema_registry_url", nullable = false, columnDefinition = "TEXT")
    private String schemaRegistryUrl;

    @Column(name = "avro_subject", nullable = false)
    private String avroSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "partitioner", nullable = false, length = 20)
    private Partitioner partitioner = Partitioner.DEFAULT;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_guarantee", nullable = false, length = 20)
    private DeliveryGuarantee deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;
}
