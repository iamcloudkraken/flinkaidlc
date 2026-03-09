---
status: success
---

# Domain Discovery Results

## Domain Model Summary

The Flink SQL Pipeline Platform is a multi-tenant SaaS system with these core entities:

- **Tenant** — a registered organization with a slug, OAuth2 FID, and resource quotas (maxPipelines, maxTotalParallelism). Each tenant gets a dedicated Kubernetes namespace on onboarding.
- **Pipeline** — a Flink SQL job definition belonging to a tenant, with status lifecycle: DRAFT → DEPLOYING → RUNNING → SUSPENDED / FAILED → DELETED.
- **PipelineSource / PipelineSink** — Kafka topic + Schema Registry configuration per pipeline (stored in DB, embedded in generated Flink SQL).
- **PipelineDeployment** — tracks the K8s resource name, ConfigMap name, job state, savepoint path, and last sync timestamp for each deployed pipeline.

Key relationships: Tenant 1→N Pipeline, Pipeline 1→N PipelineSource, Pipeline 1→N PipelineSink, Pipeline 1→1 PipelineDeployment.

The backend orchestrates Kubernetes (Fabric8 client) and Keycloak (Admin REST API) as side effects of CRUD operations. Both have NoOp stubs that activate automatically when not configured — this design is explicitly dev-friendly.

## Key Findings

### What's already local-dev friendly (built-in stubs):
1. **NoOpOAuth2ProviderClient** — activated automatically when `oauth2.admin.url` is blank. Tenant onboarding succeeds without a real Keycloak instance (but JWT validation still requires an issuer).
2. **FlinkDeploymentStatusSyncer** — gracefully degrades (logs warning, does not crash) when K8s is unavailable at startup.
3. **KubernetesConfig** — auto-falls back to `~/.kube/config` for local dev.
4. **No Kafka in Spring Boot** — Kafka config is stored per-pipeline in DB and only used in generated Flink SQL. Spring Boot starts without any Kafka dependency.

### Critical blockers for local dev:

1. **No backend Dockerfile** — the backend cannot be containerized yet.
2. **No docker-compose.yml** — nothing to `docker compose up`.
3. **No `local` Spring profile** — no `application-local.yml` exists.
4. **TenantNamespaceProvisioner has no NoOp** — calling `POST /api/v1/tenants` (tenant onboarding) will always invoke K8s. Either a kind cluster must be running, or a `NoOpTenantNamespaceProvisioner` conditional bean must be added for local profile.
5. **OAUTH2_ISSUER_URI is required** — Spring Security's JWT resource server tries to fetch the JWKS endpoint at startup. The app will not start without a reachable OIDC discovery endpoint. A local Keycloak or a mock JwtDecoder bean in the `local` profile is required.
6. **SchemaRegistryValidationService SSRF block** — the service blocks `localhost`, `10.*`, `192.168.*`, and `172.16-31.*`. Docker bridge network IPs (typically `172.17-20.x.x`) are blocked. Creating a pipeline with a Docker-internal Schema Registry URL will fail. The local setup must address this (service name resolution, network mode, or SSRF bypass for local profile).

### Architecture decisions needed:
- **K8s for tenant operations**: Use a kind cluster (full fidelity, matching the intent brief) vs. add a NoOp provisioner for the local profile. The brief says "kind cluster", so the primary path should be kind, but a lightweight mode without kind would reduce onboarding friction.
- **JWT for local dev**: Run Keycloak in Docker Compose (real tokens with `tenant_id` claim) vs. a mock `JwtDecoder` bean that accepts any token (simpler, like TestSecurityConfig). The brief says Keycloak in Docker Compose.
- **Schema Registry SSRF**: Either disable SSRF checks in the local profile, or expose schema-registry on a hostname that bypasses the check.

### Services required in Docker Compose:
| Service | Image | Port | Purpose |
|---|---|---|---|
| postgres | postgres:16-alpine | 5432 | Primary database |
| keycloak | quay.io/keycloak/keycloak | 8180 | JWT issuer + admin API |
| zookeeper | confluentinc/cp-zookeeper or bitnami | 2181 | Kafka dependency |
| kafka | confluentinc/cp-kafka or bitnami | 9092 | Pipeline source/sink (optional for UI CRUD) |
| schema-registry | confluentinc/cp-schema-registry | 8081 | Avro schema validation (required for pipeline creation) |
| backend | built from new Dockerfile | 8080 | Spring Boot API |
| frontend | frontend/Dockerfile | 80 | nginx serving React SPA + proxy |

### Seed data requirements:
- The seed script must insert a `tenants` row AND a `pipelines` row with associated `pipeline_sources` and `pipeline_sinks` rows.
- The tenant must match a Keycloak user's `tenant_id` claim so the UI can authenticate and see data.
- If using kind, the seed should also trigger `POST /api/v1/tenants` so the K8s namespace is provisioned.

### Kind cluster requirements:
- Flink Kubernetes Operator Helm chart (provides `FlinkDeployment` CRD)
- MinIO (provides S3-compatible storage for Flink checkpoints/savepoints)
- `kubeconfig` pointing at the kind cluster (Fabric8 will auto-discover it)
- The Spring Boot backend container (or process) must have network access to the kind cluster API server

## Open Questions

1. **NoOpTenantNamespaceProvisioner for lightweight mode?** The brief says "kind cluster" is the target, but should there also be a `--no-kind` or `SKIP_K8S=true` mode where tenant namespace provisioning is a no-op, to support developers who can't run kind (e.g., limited RAM)?

2. **SSRF bypass strategy for Schema Registry:** Should we (a) add a `local` profile property that disables SSRF checks, (b) route schema-registry through a public hostname, or (c) modify the SSRF check to allow Docker service names? Option (a) is the simplest for local dev.

3. **Keycloak complexity:** Keycloak requires a realm import JSON with a custom claim mapper that puts `tenant_id` in JWTs. Should the Keycloak setup include a pre-configured realm export, or should the seed script configure it via Admin API?

4. **MinIO in Docker Compose vs. kind only:** Should MinIO be part of the Docker Compose (accessible from both the backend and the kind Flink pods), or only inside the kind cluster? If inside kind only, how do Flink pods access it?

5. **Backend Dockerfile base image:** Should the backend use a `eclipse-temurin:21-jre-alpine` base, or the Spring Boot layered-jar approach with a dedicated builder stage?
