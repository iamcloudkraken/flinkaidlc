package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@DiscriminatorValue("KAFKA")
@Getter
@Setter
@NoArgsConstructor
public class KafkaPipelineSink extends PipelineSink {

    @Column(name = "topic")
    private String topic;

    @Column(name = "bootstrap_servers", columnDefinition = "TEXT")
    private String bootstrapServers;

    @Column(name = "schema_registry_url", columnDefinition = "TEXT")
    private String schemaRegistryUrl;

    @Column(name = "avro_subject")
    private String avroSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "partitioner", length = 20)
    private Partitioner partitioner = Partitioner.DEFAULT;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_guarantee", length = 20)
    private DeliveryGuarantee deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;
}
