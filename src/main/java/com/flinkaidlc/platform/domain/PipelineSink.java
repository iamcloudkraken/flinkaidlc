package com.flinkaidlc.platform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "pipeline_sinks")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "sink_type", discriminatorType = DiscriminatorType.STRING)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sinkType", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = KafkaPipelineSink.class, name = "KAFKA"),
    @JsonSubTypes.Type(value = S3PipelineSink.class, name = "S3")
})
@Getter
@Setter
@NoArgsConstructor
public abstract class PipelineSink {

    @Id
    @UuidGenerator
    @Column(name = "sink_id", updatable = false, nullable = false)
    private UUID sinkId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(name = "table_name", nullable = false)
    private String tableName;
}
