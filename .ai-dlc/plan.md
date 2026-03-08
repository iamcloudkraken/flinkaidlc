# Tactical Execution Plan: unit-01-data-model-and-foundation

## Overview

Bootstrap a Spring Boot 3.x / Java 21 Maven project at root of this worktree with:
- PostgreSQL schema via three Flyway migrations
- Five JPA entities and three Spring Data repositories
- Spring Security OAuth2 Resource Server with custom JWT principal
- Fabric8 Kubernetes client bean
- RFC 7807 GlobalExceptionHandler
- application.yml with env-var-backed properties
- Integration tests (Testcontainers PostgreSQL) covering all success criteria

---

## Step 1 — Create Maven Project Structure

1.1. Create the root `pom.xml` in the worktree root with the following configuration:
- `groupId`: `com.flinkaidlc`
- `artifactId`: `platform`
- `version`: `0.0.1-SNAPSHOT`
- `packaging`: `jar`
- `java.version`: `21`
- Parent: `spring-boot-starter-parent` version `3.2.x` (use latest 3.2.x stable)
- Dependencies:
  - `spring-boot-starter-web`
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-security`
  - `spring-boot-starter-oauth2-resource-server`
  - `spring-boot-starter-actuator`
  - `flyway-core`
  - `flyway-database-postgresql`
  - `postgresql` (runtime scope)
  - `io.fabric8:kubernetes-client` version `6.13.x` (latest 6.x — pin exact version)
  - `org.projectlombok:lombok` (optional, annotation-processor)
  - Test dependencies:
    - `spring-boot-starter-test`
    - `spring-security-test`
    - `org.testcontainers:junit-jupiter`
    - `org.testcontainers:postgresql`
    - `com.h2database:h2` (NOT used for JPA — only for potential utility; Testcontainers is primary)
- Build plugins:
  - `spring-boot-maven-plugin` (exclude Lombok from repackage)
  - `maven-compiler-plugin` configured for Java 21

1.2. Create the main application source directory tree:
```
src/
  main/
    java/com/flinkaidlc/platform/
      FlinkPlatformApplication.java       (main class)
      config/
      domain/
      exception/
      repository/
    resources/
      application.yml
      db/migration/
  test/
    java/com/flinkaidlc/platform/
      FlinkPlatformApplicationTests.java
      domain/
      security/
```

1.3. Create `FlinkPlatformApplication.java`:
```java
package com.flinkaidlc.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FlinkPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlinkPlatformApplication.class, args);
    }
}
```

---

## Step 2 — Flyway Migrations

Create three SQL files under `src/main/resources/db/migration/`.

2.1. `V1__create_tenants.sql`:
```sql
CREATE TABLE tenants (
  tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(63) UNIQUE NOT NULL,
  name VARCHAR(255) NOT NULL,
  contact_email VARCHAR(255) NOT NULL,
  fid VARCHAR(255) UNIQUE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  max_pipelines INT NOT NULL DEFAULT 10,
  max_total_parallelism INT NOT NULL DEFAULT 50,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

2.2. `V2__create_pipelines.sql`:
```sql
CREATE TABLE pipelines (
  pipeline_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(tenant_id),
  name VARCHAR(255) NOT NULL,
  description TEXT,
  sql_query TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  parallelism INT NOT NULL DEFAULT 1,
  checkpoint_interval_ms BIGINT NOT NULL DEFAULT 60000,
  upgrade_mode VARCHAR(20) NOT NULL DEFAULT 'SAVEPOINT',
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

2.3. `V3__create_pipeline_deployments.sql`:
```sql
CREATE TABLE pipeline_deployments (
  pipeline_id UUID PRIMARY KEY REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
  version INT NOT NULL DEFAULT 1,
  k8s_resource_name VARCHAR(255),
  configmap_name VARCHAR(255),
  flink_job_id VARCHAR(255),
  lifecycle_state VARCHAR(30),
  job_state VARCHAR(20),
  last_savepoint_path TEXT,
  error_message TEXT,
  last_synced_at TIMESTAMPTZ
);
```

---

## Step 3 — Domain Enums

Create enum classes under `com.flinkaidlc.platform.domain` before entities (entities depend on them).

3.1. `TenantStatus.java`:
```java
package com.flinkaidlc.platform.domain;
public enum TenantStatus { ACTIVE, SUSPENDED, DELETED }
```

3.2. `PipelineStatus.java`:
```java
package com.flinkaidlc.platform.domain;
public enum PipelineStatus { DRAFT, DEPLOYING, RUNNING, SUSPENDED, FAILED, DELETED }
```

3.3. `UpgradeMode.java`:
```java
package com.flinkaidlc.platform.domain;
public enum UpgradeMode { SAVEPOINT, LAST_STATE, STATELESS }
```

3.4. `StartupMode.java`:
```java
package com.flinkaidlc.platform.domain;
public enum StartupMode { GROUP_OFFSETS, EARLIEST, LATEST }
```

3.5. `Partitioner.java`:
```java
package com.flinkaidlc.platform.domain;
public enum Partitioner { DEFAULT, FIXED, ROUND_ROBIN }
```

3.6. `DeliveryGuarantee.java`:
```java
package com.flinkaidlc.platform.domain;
public enum DeliveryGuarantee { AT_LEAST_ONCE, EXACTLY_ONCE }
```

3.7. `JobState.java`:
```java
package com.flinkaidlc.platform.domain;
public enum JobState { RUNNING, FAILED, SUSPENDED }
```

---

## Step 4 — JPA Entities

All entities go under `com.flinkaidlc.platform.domain`. Use `jakarta.persistence` imports (not `javax.persistence`). Use `@UuidGenerator` strategy for UUID PKs. Use `@Enumerated(EnumType.STRING)` for all enum fields. Use Lombok `@Getter @Setter @NoArgsConstructor` to reduce boilerplate. Do NOT add `@Transactional` here.

4.1. `Tenant.java`:
- Table: `tenants`
- Fields: `tenantId` (UUID, PK, `@UuidGenerator`), `slug`, `name`, `contactEmail`, `fid`, `status` (`TenantStatus` enum), `maxPipelines`, `maxTotalParallelism`, `createdAt` (`OffsetDateTime`), `updatedAt` (`OffsetDateTime`)
- `@Column` names must snake_case match the migration (e.g. `contact_email`, `max_pipelines`, `created_at`)
- `createdAt`/`updatedAt` annotated with `@Column(updatable=false)` / `@Column` respectively; use `@PrePersist`/`@PreUpdate` lifecycle callbacks to set them automatically

4.2. `Pipeline.java`:
- Table: `pipelines`
- Fields: `pipelineId` (UUID, PK), `tenantId` (UUID — plain column, not a `@ManyToOne` join to Tenant), `name`, `description`, `sqlQuery`, `status` (`PipelineStatus`), `parallelism`, `checkpointIntervalMs`, `upgradeMode` (`UpgradeMode`), `createdAt`, `updatedAt`
- `@OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)` for `sources` (`List<PipelineSource>`)
- `@OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)` for `sinks` (`List<PipelineSink>`)
- `@OneToOne(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)` for `deployment` (`PipelineDeployment`)
- Lifecycle callbacks for `createdAt`/`updatedAt`

4.3. `PipelineSource.java`:
- Table: `pipeline_sources`
- Fields: `sourceId` (UUID, PK), `pipeline` (`@ManyToOne @JoinColumn(name="pipeline_id")` — owning side), `tableName`, `topic`, `bootstrapServers`, `consumerGroup`, `startupMode` (`StartupMode`), `schemaRegistryUrl`, `avroSubject`, `watermarkColumn` (nullable), `watermarkDelayMs` (nullable), `extraProperties` (String mapped as `JSONB` — use `@Column(columnDefinition = "jsonb")` with default `"{}"`)

4.4. `PipelineSink.java`:
- Table: `pipeline_sinks`
- Fields: `sinkId` (UUID, PK), `pipeline` (`@ManyToOne @JoinColumn(name="pipeline_id")` — owning side), `tableName`, `topic`, `bootstrapServers`, `schemaRegistryUrl`, `avroSubject`, `partitioner` (`Partitioner`), `deliveryGuarantee` (`DeliveryGuarantee`)

4.5. `PipelineDeployment.java`:
- Table: `pipeline_deployments`
- PK is also the FK: `pipelineId` (UUID), annotated with `@Id` and `@OneToOne @MapsId @JoinColumn(name="pipeline_id")` pattern — use `@OneToOne(fetch = FetchType.LAZY)` for the `pipeline` back-reference
- Fields: `version`, `k8sResourceName`, `configmapName`, `flinkJobId`, `lifecycleState` (String — mirrors arbitrary operator states), `jobState` (`JobState` enum — nullable), `lastSavepointPath`, `errorMessage`, `lastSyncedAt` (`OffsetDateTime`)

---

## Step 5 — Spring Data Repositories

Create repository interfaces under `com.flinkaidlc.platform.repository`.

5.1. `TenantRepository.java`:
```java
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    Optional<Tenant> findByFid(String fid);
}
```

5.2. `PipelineRepository.java`:
```java
public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByTenantId(UUID tenantId);
    long countByTenantIdAndStatusNot(UUID tenantId, PipelineStatus status);
}
```

5.3. `PipelineDeploymentRepository.java`:
```java
public interface PipelineDeploymentRepository extends JpaRepository<PipelineDeployment, UUID> {
    // No custom methods needed for this unit
}
```

---

## Step 6 — Spring Security Configuration

All classes under `com.flinkaidlc.platform.config` and `com.flinkaidlc.platform.security`.

6.1. Create `TenantAuthenticationPrincipal.java` under `com.flinkaidlc.platform.security`:
```java
package com.flinkaidlc.platform.security;

import java.util.UUID;

public record TenantAuthenticationPrincipal(UUID tenantId) {}
```

6.2. Create `TenantJwtAuthenticationConverter.java` under `com.flinkaidlc.platform.security`:
- Implements `Converter<Jwt, AbstractAuthenticationToken>`
- Reads claim name from a configurable property: inject `@Value("${jwt.tenant-id-claim:tenant_id}")` String `tenantIdClaim`
- Extract the claim value as a String, parse to UUID
- Return `JwtAuthenticationToken` constructed with the JWT, authorities, and set principal as `TenantAuthenticationPrincipal`
- If the claim is missing, throw `BadCredentialsException` with message `"JWT missing tenant_id claim"`

6.3. Create `SecurityConfig.java` under `com.flinkaidlc.platform.config`:
- `@Configuration @EnableWebSecurity`
- `@Bean SecurityFilterChain securityFilterChain(HttpSecurity http, TenantJwtAuthenticationConverter converter)`
- Configure:
  - `csrf.disable()`
  - `sessionManagement` → `STATELESS`
  - `authorizeHttpRequests`:
    - `POST /api/v1/tenants` → `permitAll()`
    - `GET /actuator/health` → `permitAll()`
    - `GET /actuator/info` → `permitAll()`
    - `/api/**` → `authenticated()`
  - `oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))`

6.4. Add to `application.yml` (under Step 8 below):
```yaml
jwt:
  tenant-id-claim: tenant_id
```

---

## Step 7 — Fabric8 Kubernetes Client Bean

Create `KubernetesConfig.java` under `com.flinkaidlc.platform.config`:

7.1. `@Configuration` class with two `@Bean` methods:

```java
@Bean
public KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
    // Auto-detects: in-cluster service account when KUBERNETES_SERVICE_HOST is set,
    // falls back to ~/.kube/config for local dev
}

@Bean
public MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
flinkDeploymentClient(KubernetesClient client) {
    return client.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment");
}
```

Note: The second bean return type is `MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>` — import from `io.fabric8.kubernetes.client.dsl`.

---

## Step 8 — application.yml

Create `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USER}
    password: ${DATABASE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OAUTH2_ISSUER_URI}
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,info

flink:
  sql-runner:
    image: ${FLINK_SQL_RUNNER_IMAGE:flink-sql-runner:latest}
  state:
    s3-bucket: ${FLINK_STATE_S3_BUCKET:flink-state}

kubernetes:
  namespace-prefix: "tenant-"

jwt:
  tenant-id-claim: tenant_id
```

---

## Step 9 — GlobalExceptionHandler

Create `GlobalExceptionHandler.java` under `com.flinkaidlc.platform.exception`:

9.1. Create custom exception classes (same package) to be referenced:
- `ResourceLimitExceededException extends RuntimeException` (used for 429)
- Note: `EntityNotFoundException` is `jakarta.persistence.EntityNotFoundException`; `AccessDeniedException` is `org.springframework.security.access.AccessDeniedException`; `ValidationException` is `jakarta.validation.ValidationException`

9.2. `GlobalExceptionHandler.java`:
- Annotated `@RestControllerAdvice`
- All handler methods return `ResponseEntity<ProblemDetail>` (Spring 6 built-in `org.springframework.http.ProblemDetail`)
- Set `Content-Type: application/problem+json` on all responses
- Mappings:
  - `@ExceptionHandler(EntityNotFoundException.class)` → status 404, title "Not Found"
  - `@ExceptionHandler(AccessDeniedException.class)` → status 403, title "Forbidden"
  - `@ExceptionHandler(ValidationException.class)` → status 400, title "Bad Request"
  - `@ExceptionHandler(ResourceLimitExceededException.class)` → status 429, title "Too Many Requests"
- Each handler:
  1. Creates `ProblemDetail.forStatusAndDetail(HttpStatus.xxx, exception.getMessage())`
  2. Sets `problemDetail.setType(URI.create("about:blank"))` (or a descriptive URI)
  3. Sets `problemDetail.setTitle("...")` — already set by `forStatusAndDetail` but can override
  4. Returns `ResponseEntity.status(httpStatus).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problemDetail)`

---

## Step 10 — Integration Tests

All tests under `src/test/java/com/flinkaidlc/platform/`.

10.1. Create `AbstractIntegrationTest.java` (base class for all integration tests):
- Annotated `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`
- Annotated `@Testcontainers`
- Declare a static `@Container PostgreSQLContainer<?> postgres` using image `postgres:16-alpine`
- Use `@DynamicPropertySource` to set:
  - `spring.datasource.url` → `postgres.getJdbcUrl()`
  - `spring.datasource.username` → `postgres.getUsername()`
  - `spring.datasource.password` → `postgres.getPassword()`
  - `spring.security.oauth2.resourceserver.jwt.issuer-uri` → `"http://localhost:9999/auth/realms/test"` (placeholder — security autoconfiguration will fail without a real issuer, so we must disable JWT validation in tests)
- To disable JWT issuer validation in tests, create `src/test/resources/application-test.yml`:
  ```yaml
  spring:
    security:
      oauth2:
        resourceserver:
          jwt:
            issuer-uri: ""
            jwk-set-uri: ""   # left empty; override security config in test slice
  ```
  **Alternative (preferred)**: Use `@TestConfiguration` that replaces `SecurityFilterChain` with `permitAll()` for the full integration test context so no live JWT issuer is needed.

10.2. Create `src/test/resources/application-test.yml` with test-specific overrides:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```
Tests will activate this with `@ActiveProfiles("test")`.

10.3. `FlywayMigrationIntegrationTest.java` (success criterion 1 — migrations apply cleanly):
- Extends `AbstractIntegrationTest`, `@ActiveProfiles("test")`
- Inject `DataSource dataSource`
- Test method `migrationsApplyAndAllTablesExist()`:
  - Query `information_schema.tables` for table names `tenants`, `pipelines`, `pipeline_sources`, `pipeline_sinks`, `pipeline_deployments`
  - Assert all five exist
  - Query each table to verify key columns and constraints (check `NOT NULL` columns exist via `information_schema.columns`)

10.4. `EntityPersistenceIntegrationTest.java` (success criterion 2 — entities persist and retrieve):
- Extends `AbstractIntegrationTest`, `@ActiveProfiles("test")`
- Annotated `@Transactional` on the test class (rolls back after each test)
- Inject `TenantRepository`, `PipelineRepository`, `PipelineDeploymentRepository`, `EntityManager`
- Test `persistAndRetrieveTenant()`:
  - Build `Tenant` with all required fields (slug, name, contactEmail, fid, status ACTIVE)
  - Save via `tenantRepository.save(tenant)`
  - Flush and clear EntityManager
  - Load by ID, assert all fields equal original
- Test `persistAndRetrievePipelineWithSourcesAndSinks()`:
  - Create and save a `Tenant`
  - Create `Pipeline` with one `PipelineSource` and one `PipelineSink`
  - Add source/sink to pipeline collections, set back-references (`source.setPipeline(pipeline)`)
  - Save pipeline
  - Flush and clear
  - Load pipeline by ID, assert sources and sinks loaded with correct fields
- Test `persistAndRetrievePipelineDeployment()`:
  - Create pipeline (with tenant), create `PipelineDeployment`, associate via `deployment.setPipeline(pipeline)` and set on pipeline
  - Save pipeline
  - Flush and clear
  - Load deployment by pipeline ID, assert fields

10.5. `SecurityIntegrationTest.java` (success criteria 3 and 4 — security rules):
- Annotated `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Testcontainers`, `@ActiveProfiles("test")`
- Use `@DynamicPropertySource` (same Postgres container as above)
- Inject `TestRestTemplate restTemplate`
- Test `postTenantsIsPermittedWithoutAuth()`:
  - POST to `/api/v1/tenants` with no Authorization header
  - Expect response is NOT 401 (it will be 400/422 since no endpoint exists yet, but NOT 401 — this verifies permit-all works)
  - Assert status != 401
- Test `getAnyApiEndpointReturns401WithoutJwt()`:
  - GET to `/api/v1/pipelines` with no Authorization header
  - Assert status == 401
- Test `validJwtWithTenantIdClaimPopulatesPrincipal()`:
  - This test requires a mock JWT. Use `@Import(TestSecurityConfig.class)` where `TestSecurityConfig` provides a `JwtDecoder` bean that accepts any token and returns a `Jwt` with `tenant_id` claim set to a test UUID
  - Create a signed JWT using `JwtClaimsSet` / `NimbusJwtEncoder` with a test RSA key, or simply mock `JwtDecoder` with Mockito
  - Create a minimal `@RestController` in the test package (or use `@WebMvcTest` slice instead) to expose a test endpoint that returns `@AuthenticationPrincipal TenantAuthenticationPrincipal`
  - **Simpler approach**: Use `@WebMvcTest` slice for this test only — test `TenantJwtAuthenticationConverter` in isolation by calling `convert(Jwt)` directly with a mock `Jwt` containing the `tenant_id` claim, then asserting the returned `AbstractAuthenticationToken` has a principal of type `TenantAuthenticationPrincipal` with correct UUID

10.6. `KubernetesClientBeanTest.java` (success criterion 5 — KubernetesClient initializes):
- Standard `@SpringBootTest` with `@ActiveProfiles("test")` and Testcontainers Postgres
- Inject `KubernetesClient kubernetesClient` and `MixedOperation<?, ?, ?> flinkDeploymentClient`
- Test `kubernetesClientBeanInitializesWithoutError()`:
  - Assert `kubernetesClient != null`
  - Assert `flinkDeploymentClient != null`
  - Note: The client will attempt to read `~/.kube/config` or service account — if neither exists the client still constructs (it only fails on actual API calls). The test should just verify bean creation succeeds.

10.7. `GlobalExceptionHandlerIntegrationTest.java` (success criterion 6 — problem+json):
- Use `@WebMvcTest` slice with `@Import(GlobalExceptionHandler.class)`
- Create a `@TestController` (inner `@RestController` in test class) with endpoints that throw each mapped exception type
- Test for each exception:
  - Assert response `Content-Type` equals `application/problem+json`
  - Assert response body contains `type`, `title`, `status`, `detail` fields (parse as `Map<String, Object>`)
  - Assert correct HTTP status code (404, 403, 400, 429)

---

## Step 11 — Test Infrastructure Support

11.1. Create `TestSecurityConfig.java` under `src/test/java/com/flinkaidlc/platform/config`:
- Annotated `@TestConfiguration`
- Provides `@Bean @Primary JwtDecoder jwtDecoder()` that returns a mock decoder accepting any token
- Use `Mockito.mock(JwtDecoder.class)` or a lambda-based `JwtDecoder` that constructs a `Jwt` with a hardcoded test `tenant_id` UUID

11.2. Ensure `src/test/resources/application-test.yml` disables real JWT issuer validation:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri:   # empty string disables issuer validation
```

---

## Step 12 — Build Verification

12.1. Run `./mvnw clean verify` (or `mvn clean verify`) — all tests should pass.

12.2. Verify Flyway migrations apply (tests will confirm this via Testcontainers).

12.3. Verify no JPA validation errors (`ddl-auto: validate`) — schema must match entities exactly.

---

## Dependency Version Reference

| Artifact | Version |
|---|---|
| spring-boot-starter-parent | 3.2.5 |
| io.fabric8:kubernetes-client | 6.13.1 |
| org.testcontainers:testcontainers-bom | 1.19.8 |
| postgresql (driver) | 42.7.x (managed by Spring Boot) |
| lombok | 1.18.x (managed by Spring Boot) |

> Always use the Spring Boot dependency management BOM for Spring-managed artifacts. Only specify versions explicitly for Fabric8 and Testcontainers.

---

## File Creation Order (Builder Checklist)

- [ ] 1. `pom.xml`
- [ ] 2. `FlinkPlatformApplication.java`
- [ ] 3. `src/main/resources/db/migration/V1__create_tenants.sql`
- [ ] 4. `src/main/resources/db/migration/V2__create_pipelines.sql`
- [ ] 5. `src/main/resources/db/migration/V3__create_pipeline_deployments.sql`
- [ ] 6. `domain/TenantStatus.java`, `PipelineStatus.java`, `UpgradeMode.java`, `StartupMode.java`, `Partitioner.java`, `DeliveryGuarantee.java`, `JobState.java`
- [ ] 7. `domain/Tenant.java`
- [ ] 8. `domain/Pipeline.java`
- [ ] 9. `domain/PipelineSource.java`
- [ ] 10. `domain/PipelineSink.java`
- [ ] 11. `domain/PipelineDeployment.java`
- [ ] 12. `repository/TenantRepository.java`
- [ ] 13. `repository/PipelineRepository.java`
- [ ] 14. `repository/PipelineDeploymentRepository.java`
- [ ] 15. `security/TenantAuthenticationPrincipal.java`
- [ ] 16. `security/TenantJwtAuthenticationConverter.java`
- [ ] 17. `config/SecurityConfig.java`
- [ ] 18. `config/KubernetesConfig.java`
- [ ] 19. `exception/ResourceLimitExceededException.java`
- [ ] 20. `exception/GlobalExceptionHandler.java`
- [ ] 21. `src/main/resources/application.yml`
- [ ] 22. `src/test/resources/application-test.yml`
- [ ] 23. `test/config/TestSecurityConfig.java`
- [ ] 24. `test/AbstractIntegrationTest.java`
- [ ] 25. `test/FlywayMigrationIntegrationTest.java`
- [ ] 26. `test/EntityPersistenceIntegrationTest.java`
- [ ] 27. `test/SecurityIntegrationTest.java`
- [ ] 28. `test/KubernetesClientBeanTest.java`
- [ ] 29. `test/GlobalExceptionHandlerIntegrationTest.java`
- [ ] 30. Run `mvn clean verify` — all green

---

## Key Implementation Notes

- **UUID generation**: Use Hibernate `@UuidGenerator` (not deprecated `@GeneratedValue(strategy=AUTO)` UUID hack). Import `org.hibernate.annotations.UuidGenerator`.
- **JSONB column**: Map `extraProperties` as `String` with `@Column(columnDefinition = "jsonb")`. For type-safe JSON, optionally add a Hibernate `@Type` from `hypersistence-utils`, but for this unit plain String is acceptable.
- **Bidirectional relationships**: Always set both sides. In `Pipeline`, add helper methods `addSource(PipelineSource s)` and `addSink(PipelineSink s)` that set the back-reference and add to the collection.
- **`ddl-auto: validate`**: Flyway owns the schema; Hibernate only validates. Column names in `@Column(name=...)` must exactly match migration SQL.
- **Security test slice**: For `SecurityIntegrationTest`, mock the `JwtDecoder` bean rather than standing up a real OAuth2 server. Spring Boot's `@MockBean JwtDecoder` in the test class will replace the auto-configured one.
- **Fabric8 client fall-through**: `KubernetesClientBuilder().build()` will not throw even if no kubeconfig exists — it creates a client configured with a default/empty context. This is intentional for testability.
- **No `@Transactional` in entities or repositories**: Transaction boundaries belong to the service layer (units 02–04).
