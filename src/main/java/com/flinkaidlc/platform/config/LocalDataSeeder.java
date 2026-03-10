package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
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

    private final TenantRepository tenantRepository;
    private final PipelineRepository pipelineRepository;
    private final JdbcTemplate jdbcTemplate;

    public LocalDataSeeder(TenantRepository tenantRepository, PipelineRepository pipelineRepository,
                           JdbcTemplate jdbcTemplate) {
        this.tenantRepository = tenantRepository;
        this.pipelineRepository = pipelineRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (tenantRepository.count() > 0) {
            log.info("[local] Seed data already present, skipping.");
            return;
        }

        log.info("[local] Seeding demo tenant and pipeline...");

        // Insert tenant directly via JDBC to ensure the deterministic UUID is used,
        // bypassing Hibernate's @UuidGenerator which ignores pre-set values on merge().
        jdbcTemplate.update(
            "INSERT INTO tenants (tenant_id, slug, name, contact_email, fid, status, " +
            "max_pipelines, max_total_parallelism, created_at, updated_at) " +
            "VALUES (?, 'demo', 'Demo Org', 'dev@local.dev', 'demo-fid-local', 'ACTIVE', " +
            "10, 20, NOW(), NOW())",
            DEMO_TENANT_ID
        );

        log.info("[local] Created demo tenant: id={} slug=demo", DEMO_TENANT_ID);

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
        log.info("[local] Seed complete. Open http://localhost:3000 to get started.");
    }
}
