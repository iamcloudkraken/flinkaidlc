package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
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

    public LocalDataSeeder(TenantRepository tenantRepository, PipelineRepository pipelineRepository) {
        this.tenantRepository = tenantRepository;
        this.pipelineRepository = pipelineRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (tenantRepository.count() > 0) {
            log.info("[local] Seed data already present, skipping.");
            return;
        }

        log.info("[local] Seeding demo tenant and pipeline...");

        // Create demo tenant
        Tenant tenant = new Tenant();
        setTenantId(tenant, DEMO_TENANT_ID);
        tenant.setSlug("demo");
        tenant.setName("Demo Org");
        tenant.setContactEmail("dev@local.dev");
        tenant.setFid("demo-fid-local");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.updateQuota(10, 20);
        tenant = tenantRepository.save(tenant);

        log.info("[local] Created demo tenant: id={} slug=demo", tenant.getTenantId());

        // Create demo pipeline
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(DEMO_TENANT_ID);
        pipeline.setName("Hello World Pipeline");
        pipeline.setDescription("Sample pipeline created by LocalDataSeeder for local development");
        pipeline.setSqlQuery("INSERT INTO output\nSELECT *\nFROM input");
        pipeline.setParallelism(1);
        pipeline.setCheckpointIntervalMs(30_000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.setStatus(PipelineStatus.DRAFT);

        PipelineSource source = new PipelineSource();
        source.setTableName("input");
        source.setTopic("demo-input");
        source.setBootstrapServers("kafka:29092");
        source.setConsumerGroup("demo-cg");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://schema-registry:8082");
        source.setAvroSubject("demo-input-value");
        source.setWatermarkDelayMs(5000);
        pipeline.addSource(source);

        PipelineSink sink = new PipelineSink();
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

    /** Reflectively set the tenantId field so the seed tenant gets a deterministic ID. */
    private void setTenantId(Tenant tenant, UUID id) {
        try {
            Field field = Tenant.class.getDeclaredField("tenantId");
            field.setAccessible(true);
            field.set(tenant, id);
        } catch (Exception e) {
            log.warn("[local] Could not set deterministic tenant ID, using generated: {}", e.getMessage());
        }
    }
}
