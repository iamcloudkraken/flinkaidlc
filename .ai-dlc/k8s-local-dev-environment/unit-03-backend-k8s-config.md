---
status: pending
depends_on: [unit-01-enterprise-namespace, unit-02-controlplane-namespace]
branch: ai-dlc/k8s-local-dev-environment/03-backend-k8s-config
discipline: backend
workflow: ""
ticket: ""
---

# unit-03-backend-k8s-config

## Description

Create the `local-k8s` Spring profile and fix two backend components that use hardcoded local values: TenantNamespaceProvisioner (NetworkPolicy label references the old "flink-platform" namespace name) and LocalDataSeeder (Kafka bootstrap server hardcoded to `localhost:9092`). No new Java classes are needed — only a new properties file and targeted fixes to two existing classes.

## Discipline

backend — Spring Boot configuration, Java.

## Domain Entities

- **application-local-k8s.properties**: New Spring properties file, activated alongside `local` profile in K8s mode. Overrides datasource URL, enables K8s provisioner, sets platform namespace and Kafka/Schema Registry addresses for in-cluster DNS.
- **TenantNamespaceProvisioner** (`com.flinkaidlc.platform.k8s.TenantNamespaceProvisioner`): Creates K8s resources per tenant. Its NetworkPolicy currently allows ingress from a namespace labeled `flink-platform` — must change to `ns-controlplane`.
- **LocalDataSeeder** (`com.flinkaidlc.platform.config.LocalDataSeeder`): Seeds Kafka topics on startup. Currently uses `localhost:9092` or Docker Compose hostname — must use in-cluster DNS in K8s mode.

## Data Sources

- `src/main/resources/application-local.properties` — existing local profile, read it to understand what `local-k8s` overrides
- `src/main/java/com/flinkaidlc/platform/k8s/TenantNamespaceProvisioner.java` — read to find the NetworkPolicy label value that needs changing
- `src/main/java/com/flinkaidlc/platform/config/LocalDataSeeder.java` — read to find where Kafka bootstrap servers are configured
- `src/main/resources/application.yml` — read for `kubernetes.namespace-prefix`, `kubernetes.platform-namespace`, and `flink.sql-runner.image` base values

## Technical Specification

### 1. Create `src/main/resources/application-local-k8s.properties`

This file is loaded when `SPRING_PROFILES_ACTIVE=local,local-k8s`. It overrides values from `application.yml` and `application-local.properties`:

```properties
# Database — Postgres running in ns-controlplane
spring.datasource.url=jdbc:postgresql://postgresql.ns-controlplane.svc.cluster.local:5432/flinkplatform
spring.datasource.username=flinkplatform
spring.datasource.password=flinkplatform

# Kafka — in ns-enterprise
spring.kafka.bootstrap-servers=kafka.ns-enterprise.svc.cluster.local:29092

# Schema Registry — in ns-enterprise
schema.registry.url=http://schema-registry.ns-enterprise.svc.cluster.local:8081

# Kubernetes provisioner — enabled (real K8s calls)
k8s.provisioner.enabled=true

# Platform namespace — where backend runs (used in NetworkPolicy)
kubernetes.platform-namespace=ns-controlplane

# Flink SQL runner image — built locally, never pulled from registry
flink.sql-runner.image=flink-sql-runner:latest

# Keep mock JWT decoder — blank issuer-uri means any Bearer token is accepted
# (avoids issuer URI mismatch between localhost tokens and in-cluster URLs)
spring.security.oauth2.resourceserver.jwt.issuer-uri=
```

**Do NOT set `kubernetes.namespace-prefix`** — it stays as `tenant-` from `application.yml`.

### 2. Fix TenantNamespaceProvisioner NetworkPolicy

File: `src/main/java/com/flinkaidlc/platform/k8s/TenantNamespaceProvisioner.java`

Find the NetworkPolicy creation block. It currently creates a NetworkPolicy that allows ingress from a namespace with label `kubernetes.io/metadata.name: flink-platform` (or similar). This must use the value from `kubernetes.platform-namespace` configuration property instead of a hardcoded string.

**Read the file first** to find the exact label key/value being used. The fix is one of:

a) If the label value is hardcoded as `"flink-platform"`: change it to inject the `kubernetes.platform-namespace` property value and use that as the namespace selector label value.

b) If it already uses a `@Value("${kubernetes.platform-namespace}")` injected field: no code change needed — just verify the property is wired correctly.

The NetworkPolicy namespace selector should match the label `kubernetes.io/metadata.name: ns-controlplane` (the value of `kubernetes.platform-namespace` in the `local-k8s` profile).

**Do not restructure the class** — make the minimum change needed: replace the hardcoded string with the injected property.

### 3. Fix LocalDataSeeder Kafka bootstrap

File: `src/main/java/com/flinkaidlc/platform/config/LocalDataSeeder.java`

**Read the file first** to find where Kafka bootstrap servers are referenced.

The seeder currently uses a hardcoded address (e.g., `kafka:9092` or `localhost:9092`) or a property that resolves to the Docker Compose value. In K8s mode it must use `kafka.ns-enterprise.svc.cluster.local:29092`.

If LocalDataSeeder already reads from `spring.kafka.bootstrap-servers`:
- No code change needed — the property is set correctly in `application-local-k8s.properties`.

If it uses a separate `@Value` property (e.g., `${kafka.bootstrap-servers}`):
- Add that property to `application-local-k8s.properties` with the K8s DNS value.

If it's hardcoded:
- Extract to a `@Value("${spring.kafka.bootstrap-servers}")` field and update `application-local-k8s.properties`.

**Read before writing** — do not assume which case applies.

## Success Criteria

- [ ] `application-local-k8s.properties` exists at `src/main/resources/application-local-k8s.properties` with all properties listed above
- [ ] Backend starts with `SPRING_PROFILES_ACTIVE=local,local-k8s` and connects to Postgres at `postgresql.ns-controlplane.svc.cluster.local:5432` (verified via `/api/v1/health` returning 200)
- [ ] `POST /api/v1/tenants` with a valid tenant payload creates a `tenant-{slug}` namespace in K8s (verified by `kubectl get ns`)
- [ ] The created tenant namespace's NetworkPolicy allows ingress from `ns-controlplane` (verified by `kubectl get networkpolicy -n tenant-{slug} -o yaml` showing `namespaceSelector: matchLabels: kubernetes.io/metadata.name: ns-controlplane`)
- [ ] LocalDataSeeder seeds Kafka topics using the in-cluster bootstrap server without errors in backend logs
- [ ] All existing tests pass: `./mvnw test`

## Risks

- **TenantNamespaceProvisioner NetworkPolicy label key**: The namespace selector label might use `kubernetes.io/metadata.name` (auto-applied by K8s to all namespaces) OR a custom label. Read the existing code before assuming. If a custom label is used, ensure `ns-controlplane` namespace in unit-02 has that label.
- **LocalDataSeeder conditional activation**: LocalDataSeeder may be `@ConditionalOnProperty` gated. In K8s mode with the `local-k8s` profile, verify the seeder is still activated (it should run to pre-populate Kafka topics for the UI demos).
- **Profile ordering**: Spring loads profiles in order. `local,local-k8s` means `local-k8s` properties override `local` properties. Verify there are no conflicting property values between the two files that could cause unexpected behavior.
- **Flyway migrations**: Flyway runs on startup. Ensure all migrations (V1–V4) pass against the in-cluster Postgres. If Postgres is freshly started (emptyDir), all migrations run from scratch — no issue. If the pod was restarted and data persisted (shouldn't happen with emptyDir), there could be conflicts.

## Boundaries

This unit does NOT handle:
- K8s manifests for deploying the backend (unit-02)
- Docker image builds (unit-04)
- Makefile targets (unit-04)
- The `local` (Docker Compose) profile — must remain unchanged
- Any frontend changes

## Notes

- The `local-k8s` profile file name follows Spring Boot convention: `application-{profile}.properties` → activated by `spring.profiles.active=local-k8s`. Spring Boot will automatically load it when the profile is active.
- Keep `spring.security.oauth2.resourceserver.jwt.issuer-uri=` blank in `local-k8s` profile. This activates the mock JWT decoder (any Bearer token accepted). Do not set a Keycloak issuer URI — the in-cluster Keycloak URL would differ from the URL in tokens issued by localhost Keycloak.
- `kubernetes.platform-namespace=ns-controlplane` is the critical property that TenantNamespaceProvisioner reads to construct the NetworkPolicy namespace selector. Getting this wrong means Flink jobs in tenant namespaces cannot receive traffic from the backend.
