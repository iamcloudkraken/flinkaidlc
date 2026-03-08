package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pipeline_deployments")
@Getter
@Setter
@NoArgsConstructor
public class PipelineDeployment {

    @Id
    @Column(name = "pipeline_id")
    private UUID pipelineId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "pipeline_id")
    private Pipeline pipeline;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "k8s_resource_name")
    private String k8sResourceName;

    @Column(name = "configmap_name")
    private String configmapName;

    @Column(name = "flink_job_id")
    private String flinkJobId;

    @Column(name = "lifecycle_state", length = 30)
    private String lifecycleState;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_state", length = 20)
    private JobState jobState;

    @Column(name = "last_savepoint_path", columnDefinition = "TEXT")
    private String lastSavepointPath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;
}
