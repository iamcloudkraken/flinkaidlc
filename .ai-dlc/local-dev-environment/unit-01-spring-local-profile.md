---
status: pending
depends_on: []
branch: ai-dlc/local-dev-environment/01-spring-local-profile
discipline: backend
workflow: ""
ticket: ""
---

# unit-01-spring-local-profile

## Description

Add a `local` Spring Boot profile to the backend that allows the application to start and serve API requests without a real Kubernetes cluster or Keycloak OIDC server. This enables both the Docker Compose stack (unit-02) and standalone backend development to work without full infrastructure.

## Discipline

backend - This unit will be executed by backend-focused agents.

## Domain Entities

- **Tenant** — `TenantNamespaceProvisioner` must be stubbed so `POST /api/v1/tenants` succeeds without a real K8s cluster
- **Pipeline** — `SchemaRegistryValidationService` SSRF blocklist must allow Docker service hostnames (`schema-registry:8081`) which resolve to `172.17-20.*` Docker bridge IPs that are currently blocked

## Data Sources

- `src/main/resources/application.properties` — existing config (postgres, OAuth2, k8s prefix)
- `src/main/java/com/flinkaidlc/platform/k8s/TenantNamespaceProvisioner.java` — calls `k8sClient` for namespace CRUD + ResourceQuota; must be conditionally no-op'd
- `src/main/java/com/flinkaidlc/platform/pipeline/SchemaRegistryValidationService.java` — contains `isPrivateIp(String host)` blocklist method; Docker bridge IPs in `172.16-31.*` are blocked
- `src/main/java/com/flinkaidlc/platform/oauth2/NoOpOAuth2ProviderClient.java` — already exists and activates when `oauth2.admin.url` is blank; no changes needed here
- `src/main/java/com/flinkaidlc/platform/FlinkPlatformApplication.java` — Spring Boot main class

## Technical Specification

### 1. `application-local.properties`

Create `src/main/resources/application-local.properties` with:

```properties
# PostgreSQL — matches Docker Compose service name and port
spring.datasource.url=jdbc:postgresql://localhost:5432/flinkplatform
spring.datasource.username=flinkplatform
spring.datasource.password=flinkplatform

# Disable K8s namespace provisioner
k8s.provisioner.enabled=false

# Disable JWT issuer-uri check (use mock decoder)
spring.security.oauth2.resourceserver.jwt.issuer-uri=

# OAuth2 admin URL blank → activates existing NoOpOAuth2ProviderClient
oauth2.admin.url=

# Kafka bootstrap (used in generated SQL only, not in Spring)
# No Spring Kafka config needed

# Schema Registry — local override; SSRF check is profile-skipped
schema.registry.url=http://localhost:8082

# Flink S3 paths — not needed locally (kind cluster handles this)
flink.s3.checkpoint-base=s3://flink-local/checkpoints
flink.s3.savepoint-base=s3://flink-local/savepoints

# Logging
logging.level.com.flinkaidlc=DEBUG
```

### 2. `NoOpTenantNamespaceProvisioner`

Create `src/main/java/com/flinkaidlc/platform/k8s/NoOpTenantNamespaceProvisioner.java`:

```java
package com.flinkaidlc.platform.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * No-op provisioner for local development (k8s.provisioner.enabled=false).
 * Logs all calls without making any Kubernetes API requests.
 */
@Service
@ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "false")
public class NoOpTenantNamespaceProvisioner implements TenantNamespaceProvisioner {
    private static final Logger log = LoggerFactory.getLogger(NoOpTenantNamespaceProvisioner.class);

    @Override public void provision(String slug, int maxPipelines, int maxTotalParallelism) {
        log.info("[local] Skipping K8s namespace provision for slug={}", slug);
    }
    @Override public void deprovision(String slug) {
        log.info("[local] Skipping K8s namespace deprovision for slug={}", slug);
    }
    @Override public void patchResourceQuota(String slug, int maxPipelines, int maxTotalParallelism) {
        log.info("[local] Skipping K8s resource quota patch for slug={}", slug);
    }
}
```

**IMPORTANT**: The existing `TenantNamespaceProvisioner` (real implementation) must be annotated with `@ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "true", matchIfMissing = true)` so it remains default in prod.

### 3. Mock JwtDecoder for local profile

Create `src/main/java/com/flinkaidlc/platform/config/LocalSecurityConfig.java`:

```java
package com.flinkaidlc.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Local dev security: provides a mock JwtDecoder that accepts any bearer token
 * and returns a JWT with a fixed tenant_id claim.
 *
 * NEVER included in production (profile = "local" only).
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    private static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
            .header("alg", "none")
            .claim("sub", "local-dev-user")
            .claim("tenant_id", LOCAL_TENANT_ID)
            .claim("scope", "openid")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }
}
```

This means any Bearer token (e.g. `Authorization: Bearer dev-token`) will be accepted locally without a real Keycloak.

### 4. SSRF blocklist local bypass

In `SchemaRegistryValidationService`, modify `isPrivateIp()` (or its call site) to be skipped when the Spring profile includes `local`:

Option A — inject `Environment` and skip when profile is `local`:
```java
@Autowired private Environment environment;

private boolean shouldCheckSsrf() {
    return !Arrays.asList(environment.getActiveProfiles()).contains("local");
}
```

Then guard the SSRF check: `if (shouldCheckSsrf() && isPrivateIp(host)) { ... }`.

Option B — make `SchemaRegistryValidationService.validate()` a no-op bean when `local` profile (via `@Profile("!local")`) and inject a `NoOpSchemaRegistryValidationService` instead.

**Use Option A** (simpler, fewer new files). Add the `Environment` injection and profile guard only in `SchemaRegistryValidationService`.

### 5. `TenantNamespaceProvisioner` interface

Verify that `TenantNamespaceProvisioner` is an interface (required for conditional bean injection). If it is currently a concrete class, extract the interface first:

```java
public interface TenantNamespaceProvisioner {
    void provision(String slug, int maxPipelines, int maxTotalParallelism);
    void deprovision(String slug);
    void patchResourceQuota(String slug, int maxPipelines, int maxTotalParallelism);
}
```

The real implementation should be named `KubernetesTenantNamespaceProvisioner implements TenantNamespaceProvisioner` annotated with `@ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "true", matchIfMissing = true)`.

## Success Criteria

- [ ] `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run` starts successfully without a K8s cluster and without a Keycloak OIDC server
- [ ] `POST /api/v1/tenants` with any Bearer token returns 201 (no K8s calls made, logged as skipped)
- [ ] `GET /api/v1/pipelines` with any Bearer token returns 200 with an empty list
- [ ] `POST /api/v1/pipelines` with a Schema Registry URL of `http://schema-registry:8082` does NOT get rejected with SSRF error when `local` profile is active
- [ ] Real K8s provisioner still activates by default (no `k8s.provisioner.enabled` property set → matchIfMissing=true)
- [ ] Unit test: `LocalSecurityConfigTest` verifies the mock JwtDecoder returns a JWT with `tenant_id` claim

## Risks

- **Interface extraction breaking existing injection**: If `TenantNamespaceProvisioner` is a class (not interface), extracting the interface may break existing `@Autowired` sites. Mitigation: search all injection points and update types.
- **Mock JwtDecoder in production**: If `@Profile("local")` is misconfigured, the mock decoder could activate in prod, bypassing all auth. Mitigation: use `@Profile("local")` (not a property), and add a unit test asserting the bean is NOT present when profile is `default`.

## Boundaries

This unit does NOT:
- Create the Docker Compose file or Keycloak realm config (unit-02)
- Create the kind cluster script (unit-03)
- Write LOCAL_DEV.md (unit-04)
- Stub `FlinkOrchestrationServiceImpl` — the existing `NoOpFlinkOrchestrationService` (`@ConditionalOnMissingBean`) handles this case already when `FlinkOrchestrationServiceImpl` is not on the classpath (it is always on the classpath, so in local mode, pipeline deploy will call the real impl which will fail gracefully — pipelines will stay in DRAFT status)

## Notes

- The `local` profile config deliberately leaves Kafka bootstrap at a non-functional default — Kafka connectivity is only needed when deploying pipelines to a real Flink cluster (unit-03). Creating/reading pipelines via the API works fine without Kafka.
- `LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001"` is a fixed UUID used in the mock JWT. The seed script (unit-02) will create a tenant with this ID so local dev has pre-seeded data matching the mock token.
- Do NOT use `@ConditionalOnMissingBean` for the NoOp provisioner — use `@ConditionalOnProperty` explicitly to avoid accidental activation.
