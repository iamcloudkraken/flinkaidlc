package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.exception.KubernetesConflictException;
import com.flinkaidlc.platform.k8s.ITenantNamespaceProvisioner;
import com.flinkaidlc.platform.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Seeds the database with demo data for local development.
 *
 * <p>Runs once after Spring context is fully started (after Flyway migrations).
 * Creates a demo tenant and a sample pipeline if the database is empty.
 *
 * <p><strong>Only active in the {@code local} Spring profile.</strong>
 *
 * <p>The demo tenant ID matches the fixed JWT claim in {@link LocalSecurityConfig},
 * so {@code Authorization: Bearer dev-token} will be authenticated as this tenant.
 */
@Component
@Profile("local")
public class LocalDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);

    /** Must match {@link LocalSecurityConfig#LOCAL_TENANT_ID} */
    private static final UUID DEMO_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Demo tenant for the K8s Flink pipeline demo (slug=10001, namespace=tenant-10001) */
    private static final UUID TENANT_10001_ID = UUID.fromString("00000000-0000-0000-0000-000000010001");

    private final PipelineRepository pipelineRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ITenantNamespaceProvisioner provisioner;

    public LocalDataSeeder(PipelineRepository pipelineRepository, JdbcTemplate jdbcTemplate,
                           ITenantNamespaceProvisioner provisioner) {
        this.pipelineRepository = pipelineRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.provisioner = provisioner;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        log.info("[local] Seeding demo tenants (idempotent)...");

        // Insert tenant directly via JDBC to ensure the deterministic UUID is used,
        // bypassing Hibernate's @UuidGenerator which ignores pre-set values on merge().
        // ON CONFLICT DO NOTHING makes this safe to re-run.
        int demoInserted = jdbcTemplate.update(
            "INSERT INTO tenants (tenant_id, slug, name, contact_email, fid, status, " +
            "max_pipelines, max_total_parallelism, created_at, updated_at) " +
            "VALUES (?, 'demo', 'Demo Org', 'dev@local.dev', 'demo-fid-local', 'ACTIVE', " +
            "10, 20, NOW(), NOW()) ON CONFLICT (tenant_id) DO NOTHING",
            DEMO_TENANT_ID
        );
        log.info("[local] Ensured demo tenant: id={} slug=demo", DEMO_TENANT_ID);
        if (demoInserted > 0) {
            provisionNamespace("demo", 10, 20);
        }

        int tenant10001Inserted = jdbcTemplate.update(
            "INSERT INTO tenants (tenant_id, slug, name, contact_email, fid, status, " +
            "max_pipelines, max_total_parallelism, created_at, updated_at) " +
            "VALUES (?, '10001', 'Tenant 10001', 'dev@10001.local', 'fid-10001-local', 'ACTIVE', " +
            "10, 20, NOW(), NOW()) ON CONFLICT (tenant_id) DO NOTHING",
            TENANT_10001_ID
        );
        log.info("[local] Ensured tenant 10001: id={} slug=10001", TENANT_10001_ID);
        if (tenant10001Inserted > 0) {
            provisionNamespace("10001", 10, 20);
        }

        if (!pipelineRepository.existsByTenantIdAndName(DEMO_TENANT_ID, "Hello World Pipeline")) {
        // Create demo pipeline using the known tenant ID
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(DEMO_TENANT_ID);
        pipeline.setName("Hello World Pipeline");
        pipeline.setDescription("Sample pipeline created by LocalDataSeeder for local development");
        pipeline.setSqlQuery("INSERT INTO output\nSELECT *\nFROM input");
        pipeline.setParallelism(1);
        pipeline.setCheckpointIntervalMs(30_000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.setStatus(PipelineStatus.DRAFT);

        KafkaPipelineSource source = new KafkaPipelineSource();
        source.setTableName("input");
        source.setTopic("demo-input");
        source.setBootstrapServers("kafka:29092");
        source.setConsumerGroup("demo-cg");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://schema-registry:8082");
        source.setAvroSubject("demo-input-value");
        source.setWatermarkDelayMs(5000L);
        pipeline.addSource(source);

        KafkaPipelineSink sink = new KafkaPipelineSink();
        sink.setTableName("output");
        sink.setTopic("demo-output");
        sink.setBootstrapServers("kafka:29092");
        sink.setSchemaRegistryUrl("http://schema-registry:8082");
        sink.setAvroSubject("demo-output-value");
        sink.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        pipeline.addSink(sink);

        pipeline = pipelineRepository.save(pipeline);
        log.info("[local] Created demo pipeline: id={} name='Hello World Pipeline'", pipeline.getPipelineId());
        } // end pipeline guard
        log.info("[local] Seed complete. Open http://localhost:3000 to get started.");
    }

    private void provisionNamespace(String slug, int maxPipelines, int maxParallelism) {
        try {
            provisioner.provision(slug, maxPipelines, maxParallelism);
            log.info("[local] Provisioned K8s namespace for slug={}", slug);
        } catch (KubernetesConflictException e) {
            log.debug("[local] Namespace for slug={} already exists, skipping", slug);
        } catch (Exception e) {
            log.warn("[local] Could not provision namespace for slug={}: {}", slug, e.getMessage());
        }
    }
}
