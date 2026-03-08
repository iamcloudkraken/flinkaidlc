---
status: pending
depends_on: []
branch: ai-dlc/flink-sql-pipeline-platform/01-data-model-and-foundation
discipline: backend
workflow: ""
ticket: ""
---

# unit-01-data-model-and-foundation

## Description
Bootstrap the Spring Boot project with PostgreSQL schema (Flyway migrations), entity models, JPA repositories, Spring Security JWT filter, and the Fabric8 Kubernetes client bean. This unit delivers the shared infrastructure that all other units depend on — no business logic, only foundation.

## Discipline
backend — executed by backend-focused agents.

## Domain Entities
All entities: Tenant, Pipeline, PipelineSource, PipelineSink, PipelineDeployment. This unit defines their database schema and JPA mappings; it does NOT implement service or controller logic.

## Data Sources
- **PostgreSQL** (platform DB): All schema creation via Flyway migrations
- **No external APIs called in this unit**

## Technical Specification

### Spring Boot Project Setup
- Java 21, Spring Boot 3.x
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `flyway-core`, `postgresql`, `fabric8-kubernetes-client`, `lombok`
- Build: Maven (`pom.xml`)
- Package structure: `com.flinkaidlc.platform`

### Flyway Migrations (src/main/resources/db/migration/)

`V1__create_tenants.sql`:
```sql
CREATE TABLE tenants (
  tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(63) UNIQUE NOT NULL,          -- used as K8s namespace suffix
  name VARCHAR(255) NOT NULL,
  contact_email VARCHAR(255) NOT NULL,
  fid VARCHAR(255) UNIQUE NOT NULL,          -- OAuth2 client ID
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED, DELETED
  max_pipelines INT NOT NULL DEFAULT 10,
  max_total_parallelism INT NOT NULL DEFAULT 50,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`V2__create_pipelines.sql`:
```sql
CREATE TABLE pipelines (
  pipeline_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(tenant_id),
  name VARCHAR(255) NOT NULL,
  description TEXT,
  sql_query TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT, DEPLOYING, RUNNING, SUSPENDED, FAILED, DELETED
  parallelism INT NOT NULL DEFAULT 1,
  checkpoint_interval_ms BIGINT NOT NULL DEFAULT 60000,
  upgrade_mode VARCHAR(20) NOT NULL DEFAULT 'SAVEPOINT',  -- SAVEPOINT, LAST_STATE, STATELESS
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_sources (
  source_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pipeline_id UUID NOT NULL REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  table_name VARCHAR(255) NOT NULL,
  topic VARCHAR(255) NOT NULL,
  bootstrap_servers TEXT NOT NULL,
  consumer_group VARCHAR(255) NOT NULL,
  startup_mode VARCHAR(20) NOT NULL DEFAULT 'GROUP_OFFSETS',
  schema_registry_url TEXT NOT NULL,
  avro_subject VARCHAR(255) NOT NULL,
  watermark_column VARCHAR(255),
  watermark_delay_ms BIGINT DEFAULT 5000,
  extra_properties JSONB DEFAULT '{}'
);

CREATE TABLE pipeline_sinks (
  sink_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pipeline_id UUID NOT NULL REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  table_name VARCHAR(255) NOT NULL,
  topic VARCHAR(255) NOT NULL,
  bootstrap_servers TEXT NOT NULL,
  schema_registry_url TEXT NOT NULL,
  avro_subject VARCHAR(255) NOT NULL,
  partitioner VARCHAR(20) NOT NULL DEFAULT 'DEFAULT',
  delivery_guarantee VARCHAR(20) NOT NULL DEFAULT 'AT_LEAST_ONCE'
);
```

`V3__create_pipeline_deployments.sql`:
```sql
CREATE TABLE pipeline_deployments (
  pipeline_id UUID PRIMARY KEY REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  version INT NOT NULL DEFAULT 1,
  k8s_resource_name VARCHAR(255),
  configmap_name VARCHAR(255),
  flink_job_id VARCHAR(255),
  lifecycle_state VARCHAR(30),    -- mirrors FlinkDeployment .status.lifecycleState
  job_state VARCHAR(20),          -- RUNNING, FAILED, SUSPENDED
  last_savepoint_path TEXT,
  error_message TEXT,
  last_synced_at TIMESTAMPTZ
);
```

### JPA Entities
Create `@Entity` classes for all five tables under `com.flinkaidlc.platform.domain`:
- `Tenant`, `Pipeline`, `PipelineSource`, `PipelineSink`, `PipelineDeployment`
- Use `@UuidGenerator` for primary keys
- Use `@Enumerated(EnumType.STRING)` for all status/enum fields
- `Pipeline` has `@OneToMany(cascade = ALL, orphanRemoval = true)` for sources and sinks
- `Pipeline` has `@OneToOne(cascade = ALL, orphanRemoval = true)` for deployment

### Spring Data Repositories
Under `com.flinkaidlc.platform.repository`:
- `TenantRepository extends JpaRepository<Tenant, UUID>` — add `findBySlug(String slug)`, `findByFid(String fid)`
- `PipelineRepository extends JpaRepository<Pipeline, UUID>` — add `findByTenantId(UUID tenantId)`, `countByTenantIdAndStatusNot(UUID tenantId, PipelineStatus status)`
- `PipelineDeploymentRepository extends JpaRepository<PipelineDeployment, UUID>`

### Spring Security JWT Configuration
`SecurityConfig.java` under `com.flinkaidlc.platform.config`:
- Configure as OAuth2 Resource Server: `http.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`
- JWT issuer URI from `application.yml`: `spring.security.oauth2.resourceserver.jwt.issuer-uri`
- Custom `JwtAuthenticationConverter` that extracts `tenant_id` claim from the JWT and populates a `TenantAuthenticationPrincipal` with `tenantId: UUID`
- Permit: `POST /api/v1/tenants` (self-service registration — unauthenticated), `GET /actuator/health`
- Require authentication for all other `/api/**` endpoints

### Fabric8 Kubernetes Client Bean
`KubernetesConfig.java` under `com.flinkaidlc.platform.config`:
- `@Bean KubernetesClient kubernetesClient()` — auto-configures from in-cluster service account when deployed in K8s; falls back to `~/.kube/config` for local dev
- `@Bean GenericKubernetesResource flinkDeploymentClient(KubernetesClient client)` — returns `client.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment")`

### Application Configuration
`application.yml`:
```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USER}
    password: ${DATABASE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OAUTH2_ISSUER_URI}
  flyway:
    enabled: true

flink:
  sql-runner:
    image: ${FLINK_SQL_RUNNER_IMAGE:flink-sql-runner:latest}
  state:
    s3-bucket: ${FLINK_STATE_S3_BUCKET:flink-state}

kubernetes:
  namespace-prefix: "tenant-"
```

### RFC 7807 Problem JSON
`GlobalExceptionHandler.java` under `com.flinkaidlc.platform.exception`:
- `@RestControllerAdvice` using Spring's `ProblemDetail` (Spring 6 built-in)
- Map `EntityNotFoundException` → `404`, `AccessDeniedException` → `403`, `ValidationException` → `400`, `ResourceLimitExceededException` → `429`
- All responses: `Content-Type: application/problem+json`

## Success Criteria
- [ ] Flyway migrations apply cleanly from a fresh PostgreSQL database — all 5 tables created with correct constraints and indexes
- [ ] All JPA entities map correctly — integration test persists and retrieves each entity type
- [ ] `POST /api/v1/tenants` is accessible without auth (permit-all); all other `/api/**` endpoints return `401` without a valid JWT
- [ ] Valid JWT with `tenant_id` claim populates `TenantAuthenticationPrincipal` accessible in controllers via `@AuthenticationPrincipal`
- [ ] `KubernetesClient` bean initializes without error in both in-cluster and local (`~/.kube/config`) modes
- [ ] All error responses use `Content-Type: application/problem+json` with `type`, `title`, `status`, `detail` fields

## Risks
- **Flyway migration ordering**: If units 02/03/04 add columns later without new migration files, schema and entities diverge. Mitigation: enforce that any schema change goes through a new `VN__*.sql` file.
- **JWT claim shape**: The `tenant_id` claim name depends on the OAuth2 provider configuration. Mitigation: make the claim name configurable via `application.yml` (`jwt.tenant-id-claim: tenant_id`).
- **Fabric8 version compatibility**: Fabric8 major versions have breaking API changes. Mitigation: pin to `6.x` and document the exact version in `pom.xml`.

## Boundaries
This unit does NOT implement any REST endpoints (except the exception handler). It does NOT call the Kubernetes API or Flink Operator. It does NOT implement tenant or pipeline business logic. Those belong to units 02, 03, and 04.

## Notes
- Use `jakarta.persistence` (not `javax.persistence`) — Spring Boot 3 requires Jakarta EE 9+
- `Pipeline.status` should be an `enum PipelineStatus` with values matching the DB constraint
- Keep `@Transactional` out of this unit — service layers in units 02/03/04 own transaction boundaries
