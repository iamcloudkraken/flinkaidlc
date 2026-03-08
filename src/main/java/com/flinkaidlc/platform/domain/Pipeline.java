package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pipelines")
@Getter
@Setter
@NoArgsConstructor
public class Pipeline {

    @Id
    @UuidGenerator
    @Column(name = "pipeline_id", updatable = false, nullable = false)
    private UUID pipelineId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "sql_query", nullable = false, columnDefinition = "TEXT")
    private String sqlQuery;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PipelineStatus status = PipelineStatus.DRAFT;

    @Column(name = "parallelism", nullable = false)
    private int parallelism = 1;

    @Column(name = "checkpoint_interval_ms", nullable = false)
    private long checkpointIntervalMs = 60000L;

    @Enumerated(EnumType.STRING)
    @Column(name = "upgrade_mode", nullable = false, length = 20)
    private UpgradeMode upgradeMode = UpgradeMode.SAVEPOINT;

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PipelineSource> sources = new ArrayList<>();

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PipelineSink> sinks = new ArrayList<>();

    @OneToOne(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    private PipelineDeployment deployment;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public void addSource(PipelineSource source) {
        source.setPipeline(this);
        sources.add(source);
    }

    public void addSink(PipelineSink sink) {
        sink.setPipeline(this);
        sinks.add(sink);
    }
}
