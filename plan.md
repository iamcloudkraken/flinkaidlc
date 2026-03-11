# Tactical Plan: unit-03-backend-k8s-config

## Key Findings

### 1. application.yml (src/main/resources/application.yml)

The base config uses environment-variable substitution for all deployment-sensitive values:

- Line 3: spring.datasource.url: ${DATABASE_URL} — no default
- Line 4: spring.datasource.username: ${DATABASE_USER} — no default
- Line 5: spring.datasource.password: ${DATABASE_PASSWORD} — no default
- Line 14: spring.security.oauth2.resourceserver.jwt.issuer-uri: ${OAUTH2_ISSUER_URI} — no default
- Line 26: flink.sql-runner.image: ${FLINK_SQL_RUNNER_IMAGE:flink-sql-runner:latest} — has default
- Line 33: kubernetes.platform-namespace: ${KUBERNETES_PLATFORM_NAMESPACE:flink-platform} — default is flink-platform

Confirmed property key: kubernetes.platform-namespace (hyphenated, not camelCase).

There is NO spring.kafka.bootstrap-servers in application.yml. Kafka bootstrap is stored as
pipeline entity data, not at the application level. schema.registry.url is also not in
application.yml (only in application-local.properties).

### 2. application-local.properties (src/main/resources/application-local.properties)

Current local profile sets:
- Line 7: spring.datasource.url=jdbc:postgresql://localhost:5432/flinkplatform
- Line 8: spring.datasource.username=flinkplatform
- Line 9: spring.datasource.password=flinkplatform
- Line 12: k8s.provisioner.enabled=false
- Line 15: spring.security.oauth2.resourceserver.jwt.issuer-uri= (blank)
- Line 18: oauth2.admin.url= (blank)
- Line 21: schema.registry.url=http://localhost:8082

The local-k8s profile will be used alongside the local profile
(SPRING_PROFILES_ACTIVE=local,local-k8s), so it only needs to override the localhost values above.

### 3. TenantNamespaceProvisioner.java

FINDING: NO CHANGE NEEDED.

The constructor at line 57 already reads platformNamespace from the property:
  @Value("${kubernetes.platform-namespace:flink-platform}") String platformNamespace

And at line 257, the NetworkPolicy namespace selector correctly uses the injected field:
  .withMatchLabels(Map.of("kubernetes.io/metadata.name", platformNamespace))

The field is NOT hardcoded. Setting kubernetes.platform-namespace=ns-controlplane in the new
properties file is sufficient.

### 4. LocalDataSeeder.java

FINDING: NO CHANGE NEEDED.

LocalDataSeeder does NOT use spring.kafka.bootstrap-servers or any @Value injection for Kafka.
Instead:
- Line 84: source.setBootstrapServers("kafka:29092") — hardcoded Docker Compose service name
- Line 95: sink.setBootstrapServers("kafka:29092") — same

These are seed data values stored as demo pipeline configuration, not Spring app connectivity.
The backend has no application-level Kafka consumer/producer. No fix needed.

---

## Changes Required

### Change 1: Create src/main/resources/application-local-k8s.properties

Content:

# =============================================
# Local K8s Development Profile
# Run with: SPRING_PROFILES_ACTIVE=local,local-k8s
# Overrides application-local.properties with in-cluster K8s service DNS names.
# =============================================

# Database -- PostgreSQL running in ns-controlplane
spring.datasource.url=jdbc:postgresql://postgresql.ns-controlplane.svc.cluster.local:5432/flinkplatform
spring.datasource.username=flinkplatform
spring.datasource.password=flinkplatform

# Kafka -- in ns-enterprise (referenced for Kafka admin/topic validation)
spring.kafka.bootstrap-servers=kafka.ns-enterprise.svc.cluster.local:29092

# Schema Registry -- in ns-enterprise
schema.registry.url=http://schema-registry.ns-enterprise.svc.cluster.local:8081

# Kubernetes provisioner -- enabled (real K8s calls against the cluster)
k8s.provisioner.enabled=true

# Platform namespace -- where the backend runs (used in NetworkPolicy ingress allow)
kubernetes.platform-namespace=ns-controlplane

# Flink SQL runner image -- built locally and loaded into the cluster
flink.sql-runner.image=flink-sql-runner:latest

# Blank issuer-uri activates mock JWT decoder (same as local profile)
spring.security.oauth2.resourceserver.jwt.issuer-uri=

### Change 2: TenantNamespaceProvisioner.java -- No change needed

The platformNamespace field is already injected from
${kubernetes.platform-namespace:flink-platform} (line 57). Setting
kubernetes.platform-namespace=ns-controlplane in the new properties file is sufficient.

### Change 3: LocalDataSeeder.java -- No change needed

No Spring application-level Kafka connectivity. The hardcoded kafka:29092 values are demo
pipeline seed data for Docker Compose, not backend infrastructure config.

---

## Implementation Steps for Builder

1. Create file: src/main/resources/application-local-k8s.properties with the exact content above.
2. No changes needed in TenantNamespaceProvisioner.java.
3. No changes needed in LocalDataSeeder.java.
4. Commit with the message below.

## Commit Message

feat(unit-03-backend-k8s-config): add local-k8s Spring profile

Adds application-local-k8s.properties activated alongside the existing
local profile (SPRING_PROFILES_ACTIVE=local,local-k8s). Overrides
localhost datasource/schema-registry URLs with in-cluster K8s service
DNS names, enables the real K8s provisioner, and sets
kubernetes.platform-namespace=ns-controlplane so TenantNamespaceProvisioner
generates correct NetworkPolicy namespace selector labels.

TenantNamespaceProvisioner already reads platformNamespace from
${kubernetes.platform-namespace} -- no Java changes needed.
LocalDataSeeder has no Spring-level Kafka connectivity -- no Java changes needed.
