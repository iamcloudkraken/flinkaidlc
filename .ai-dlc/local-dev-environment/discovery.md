---
intent: local-dev-environment
created: 2026-03-08
status: active
---

# Discovery Log: Local Dev Environment

Elaboration findings persisted during Phase 2.5 domain discovery.
Builders: read section headers for an overview, then dive into specific sections as needed.

## Project Structure

```
flink-sql-pipeline-platform/
├── pom.xml                          # Maven build — Spring Boot 3.3.10, Java 21, Fabric8 6.13.1
├── README.md                        # Minimal README (AI-DLC workflow only)
├── frontend/
│   ├── Dockerfile                   # 2-stage: node:20-alpine build + nginx:1.25-alpine serve
│   ├── nginx.conf                   # SPA + reverse proxy /api/ → http://flink-platform-backend:8080
│   ├── package.json                 # React 18, Vite 5, Axios, TanStack Query, Monaco Editor
│   ├── vite.config.ts               # Dev proxy: /api → http://localhost:8080
│   └── k8s/                         # K8s manifests for production deployment
│       ├── deployment.yaml
│       ├── service.yaml
│       └── ingress.yaml
└── src/
    ├── main/
    │   ├── java/com/flinkaidlc/platform/
    │   │   ├── FlinkPlatformApplication.java        # @SpringBootApplication @EnableAsync @EnableScheduling
    │   │   ├── config/
    │   │   │   ├── KubernetesConfig.java            # Fabric8 KubernetesClient bean (auto-detect in-cluster/kubeconfig)
    │   │   │   ├── OAuth2AdminConfig.java           # Conditional: Keycloak if oauth2.admin.url set, else NoOp
    │   │   │   ├── SecurityConfig.java              # JWT resource server, POST /api/v1/tenants permitAll
    │   │   │   └── WebMvcConfig.java                # Rate-limiter interceptor on registration endpoint
    │   │   ├── domain/                              # JPA entities: Tenant, Pipeline, PipelineSource, PipelineSink, PipelineDeployment
    │   │   ├── k8s/TenantNamespaceProvisioner.java  # Creates K8s namespace + RBAC + quota + NetworkPolicy per tenant
    │   │   ├── oauth2/
    │   │   │   ├── KeycloakOAuth2ProviderClient.java # Real Keycloak Admin API calls
    │   │   │   └── NoOpOAuth2ProviderClient.java     # Silent stub for local/test
    │   │   ├── orchestration/
    │   │   │   ├── FlinkOrchestrationServiceImpl.java  # @Primary K8s-backed: ConfigMap + FlinkDeployment CRD
    │   │   │   ├── NoOpFlinkOrchestrationService.java  # @ConditionalOnMissingBean stub (NOT activated by default)
    │   │   │   ├── FlinkDeploymentBuilder.java          # Builds FlinkDeployment CRD manifest map
    │   │   │   ├── FlinkDeploymentStatusSyncer.java     # K8s informer + @Scheduled poller → DB sync
    │   │   │   └── FlinkSqlGenerator.java               # Generates statements.sql from Pipeline domain object
    │   │   ├── pipeline/                            # PipelineController, PipelineService, SqlValidationService, SchemaRegistryValidationService
    │   │   ├── security/                            # TenantJwtAuthenticationConverter (reads tenant_id JWT claim)
    │   │   └── tenant/                              # TenantController, TenantService (onboard/update/delete)
    │   └── resources/
    │       ├── application.yml                      # All config via env vars (no defaults for DB/OAuth2)
    │       └── db/migration/
    │           ├── V1__create_tenants.sql
    │           ├── V2__create_pipelines.sql
    │           └── V3__create_pipeline_deployments.sql
    └── test/
        ├── java/...AbstractIntegrationTest.java     # TestContainers postgres:16-alpine, @ActiveProfiles("test")
        ├── java/.../config/TestSecurityConfig.java  # Mock JwtDecoder (any token → tenant_id=00000000-0000-0000-0000-000000000001)
        └── resources/application-test.yml          # Minimal overrides for test profile
```

**No Makefile, no docker-compose.yml, no scripts/ directory, no backend Dockerfile exist yet.**

## External Service Dependencies

### PostgreSQL
| Config key | Env var | Default | Notes |
|---|---|---|---|
| `spring.datasource.url` | `DATABASE_URL` | none (required) | JDBC URL e.g. `jdbc:postgresql://...` |
| `spring.datasource.username` | `DATABASE_USER` | none (required) | |
| `spring.datasource.password` | `DATABASE_PASSWORD` | none (required) | |

Tests use `postgres:16-alpine` via TestContainers.

### Keycloak / OAuth2
| Config key | Env var | Default | Notes |
|---|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `OAUTH2_ISSUER_URI` | none (required) | Used for JWT validation by Spring Security |
| `oauth2.admin.url` | `OAUTH2_ADMIN_URL` | `""` (empty) | If empty → NoOp stub used |
| `oauth2.admin.realm` | `OAUTH2_ADMIN_REALM` | `flink-platform` | Keycloak realm name |
| `oauth2.admin.client-id` | `OAUTH2_ADMIN_CLIENT_ID` | `""` | Admin client credentials |
| `oauth2.admin.client-secret` | `OAUTH2_ADMIN_CLIENT_SECRET` | `""` | Admin client secret |

**Critical insight**: When `oauth2.admin.url` is blank, the `NoOpOAuth2ProviderClient` is activated — OAuth2 operations silently succeed. This is already designed for local dev. However, `OAUTH2_ISSUER_URI` is still required for JWT validation (the resource server needs it to fetch JWKS).

### Kafka
Kafka is referenced **only inside the generated Flink SQL** (DDL `WITH` clauses), not in Spring Boot configuration. The `bootstrap_servers` and `schema_registry_url` values are stored per-pipeline in the database. Spring Boot itself has **no Kafka client dependency** at all. Kafka is needed only when deploying pipelines to a real Flink cluster.

### S3 / MinIO (Flink State Storage)
| Config key | Env var | Default | Notes |
|---|---|---|---|
| `flink.state.s3-bucket` | `FLINK_STATE_S3_BUCKET` | `flink-state` | Used in generated FlinkDeployment CRD manifest |
| `flink.image` | `FLINK_IMAGE` | `flink:1.20` | Flink container image in FlinkDeployment CRDs |
| `flink.sql-runner.image` | `FLINK_SQL_RUNNER_IMAGE` | `flink-sql-runner:latest` | |

S3/MinIO endpoint is **not configured in Spring Boot** — it's injected into the Flink pods via the CRD spec. Spring Boot only stores the bucket name for generating checkpoint/savepoint paths in CRD manifests (`s3://<bucket>/<tenantId>/<pipelineId>/checkpoints`).

### Kubernetes
| Config key | Env var | Default | Notes |
|---|---|---|---|
| `kubernetes.namespace-prefix` | — | `tenant-` | Prefix for per-tenant namespaces |
| `kubernetes.platform-namespace` | `KUBERNETES_PLATFORM_NAMESPACE` | `flink-platform` | Platform's own namespace |
| `flink.pods-per-pipeline` | `FLINK_PODS_PER_PIPELINE` | `4` | Used to compute pod quota per tenant |
| `flink.sync.poll-interval-ms` | — | `30000` | Fallback poller interval |

The Fabric8 client auto-detects: `KUBERNETES_SERVICE_HOST` for in-cluster; falls back to `~/.kube/config` for local dev. The `FlinkDeploymentStatusSyncer` logs a warning (not error) if K8s is unavailable, so the app starts without K8s access.

### Schema Registry
No global config. Each pipeline source/sink stores its own `schema_registry_url`. The `SchemaRegistryValidationService` calls the Schema Registry directly at pipeline creation time. It has an SSRF blocklist that **blocks `localhost`, `127.*`, `10.*`, `192.168.*`, `172.16-31.*`** — Docker bridge IPs typically fall in `172.17-20.x.x` range, which are blocked.

## Spring Profiles

| Profile | Location | What it does |
|---|---|---|
| (default) | `src/main/resources/application.yml` | All config; requires env vars for DB + OAuth2 |
| `test` | `src/test/resources/application-test.yml` | Minimal overrides; TestContainers provides DB; mock JwtDecoder |
| `local` | **Does not exist yet** | Must be created |

The `test` profile activates `TestSecurityConfig` which replaces `JwtDecoder` with a mock accepting any token. There is no `application-local.yml` or any existing local profile configuration.

## Kafka Configuration

Spring Boot has **no Kafka dependency** in `pom.xml` and no Kafka config in `application.yml`. Kafka connectivity details (`bootstrap_servers`, `schema_registry_url`) are stored **per-pipeline in the database** and emitted only into generated Flink SQL DDL statements. When a pipeline is deployed, the Flink SQL generator embeds the bootstrap servers into the `CREATE TABLE ... WITH (...)` block which runs inside the Flink cluster.

For local dev: Kafka + Schema Registry are needed if a developer wants to deploy a pipeline to the kind cluster. For browsing the UI / CRUD operations, they are not needed at Spring Boot startup.

## Keycloak/OAuth2 Configuration

**Two separate concerns:**

1. **JWT validation** (resource server): Spring Security needs `OAUTH2_ISSUER_URI` to fetch JWKS public keys and validate incoming JWTs. Required at startup.

2. **Admin API** (Keycloak client registration): Used when onboarding a tenant to register their OAuth2 client credentials. When `oauth2.admin.url` is blank, the `NoOpOAuth2ProviderClient` is used — onboarding succeeds but no Keycloak client is created.

**Local dev strategy (already designed in):**
- Leave `oauth2.admin.url` blank → NoOp client handles onboarding silently.
- For `OAUTH2_ISSUER_URI`: a local Keycloak instance must be provided, OR Spring Security's JWT validation must be bypassed via a local profile.

**Keycloak Admin API calls made:**
- `POST /realms/{realm}/protocol/openid-connect/token` — fetch admin token (client_credentials grant)
- `POST /admin/realms/{realm}/clients` — register new OAuth2 client on tenant onboarding
- `GET /admin/realms/{realm}/clients?clientId={fid}` — look up client UUID
- `DELETE /admin/realms/{realm}/clients/{internalId}` — delete client on tenant offboarding

**JWT claims required:** `tenant_id` (UUID string) — configured via `jwt.tenant-id-claim: tenant_id`

## PostgreSQL Configuration

- Driver: `org.postgresql:postgresql` (runtime scope)
- Migration tool: Flyway (with `flyway-database-postgresql` extension)
- JPA DDL: `validate` (Flyway is the sole schema manager)
- Connection pool: HikariCP (Spring Boot default)
- Config: entirely env-var driven — `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
- Test image: `postgres:16-alpine` via TestContainers

## S3/Storage Configuration

S3 is not accessed by Spring Boot at all. It is referenced only in `FlinkDeploymentBuilder`:
- Checkpoint dir: `s3://{flink.state.s3-bucket}/{tenantId}/{pipelineId}/checkpoints`
- Savepoint dir: `s3://{flink.state.s3-bucket}/{tenantId}/{pipelineId}/savepoints`

These paths are written into the `FlinkDeployment` CRD's `flinkConfiguration` map. The Flink pods access S3 directly using their own credentials (not Spring Boot's). For local kind cluster dev, MinIO would need to be accessible inside the kind cluster.

## Kubernetes API Calls Inventory

### TenantNamespaceProvisioner (on tenant onboard)
1. `CREATE Namespace` `tenant-{slug}` with labels `app.kubernetes.io/managed-by=flink-platform`, `tenant-slug={slug}`
2. `CREATE ServiceAccount` `flink` in `tenant-{slug}`
3. `CREATE Role` `flink` in `tenant-{slug}` — verbs on `pods`, `configmaps`, `services`, `endpoints`, `deployments`, `replicasets`
4. `CREATE RoleBinding` `flink` in `tenant-{slug}` — binds Role to ServiceAccount
5. `CREATE ResourceQuota` `tenant-quota` — limits: `pods={maxPipelines*4}`, `requests.cpu={maxTotalParallelism}`, `requests.memory={maxTotalParallelism*2}Gi`
6. `CREATE NetworkPolicy` `tenant-isolation` — deny all ingress except same-namespace and platform namespace

### TenantNamespaceProvisioner (on tenant update / quota change)
7. `CREATE OR REPLACE ResourceQuota` `tenant-quota` in `tenant-{slug}`

### TenantNamespaceProvisioner (on tenant delete)
8. `DELETE Namespace` `tenant-{slug}` (cascades all resources)

### FlinkOrchestrationServiceImpl (on pipeline deploy)
9. `CREATE OR REPLACE ConfigMap` `pipeline-sql-{pipelineId}` in `tenant-{slug}` — contains `statements.sql`
10. `CREATE OR REPLACE FlinkDeployment` CRD (`flink.apache.org/v1beta1`) `pipeline-{pipelineId}` in `tenant-{slug}`

### FlinkOrchestrationServiceImpl (on pipeline suspend/resume)
11. `PATCH FlinkDeployment` — sets `spec.job.state=suspended` or `running`

### FlinkOrchestrationServiceImpl (on pipeline teardown)
12. `PATCH FlinkDeployment` to suspended
13. `DELETE FlinkDeployment`
14. `DELETE ConfigMap`

### FlinkOrchestrationServiceImpl (on tenant suspend-all)
15. `LIST FlinkDeployments` by labels in `tenant-{slug}` namespace
16. `PATCH FlinkDeployment` for each — set state suspended

### FlinkDeploymentStatusSyncer (background)
17. `WATCH FlinkDeployments` via Shared Informer across all namespaces (at startup, gracefully degrades)
18. `LIST FlinkDeployments` across all namespaces every 30s (fallback poller)

**CRD Required:** `FlinkDeployment` (`flink.apache.org/v1beta1`) — the Flink Kubernetes Operator CRD must be installed in the cluster.

**Important:** `TenantNamespaceProvisioner` is NOT wrapped with a NoOp. Tenant creation WILL fail without K8s access. Only `FlinkOrchestrationService` (pipeline operations) has a NoOp stub.

## Frontend Build and Serve Setup

**Build:** Two-stage Docker build
- Stage 1: `node:20-alpine` — `npm ci` + `npm run build` (TypeScript compile + Vite bundle)
- Stage 2: `nginx:1.25-alpine` — serves `/usr/share/nginx/html`

**Runtime nginx config:**
- Listens on port 80
- `location /api/` proxied to `http://flink-platform-backend:8080` (hardcoded Docker service name)
- SPA fallback: `try_files $uri $uri/ /index.html`
- Security headers: X-Frame-Options DENY, CSP `connect-src 'self'`

**Dev mode (vite dev server):**
- `vite.config.ts` proxies `/api` → `http://localhost:8080`
- Can run `npm run dev` against a locally running backend without Docker

**Tech stack:** React 18, TypeScript, Vite 5, Tailwind CSS, TanStack Query, Monaco Editor, Axios, React Router v6

## Existing Dev Tooling

None exists currently:
- No `Makefile`
- No `docker-compose.yml` or `docker-compose.yaml`
- No `scripts/` directory
- No backend `Dockerfile` (only `frontend/Dockerfile` exists)
- No `.env` or `.env.example` files
- No seed data / SQL fixtures

Dev-friendly patterns already in the code:
- `KubernetesConfig` auto-falls-back to `~/.kube/config`
- `FlinkDeploymentStatusSyncer` logs a warning (not crash) if K8s informer fails
- `NoOpOAuth2ProviderClient` activated when `oauth2.admin.url` is blank
- `NoOpFlinkOrchestrationService` exists but is not activated (FlinkOrchestrationServiceImpl is @Primary)

## Flyway Migrations

### V1: `tenants`
| Column | Type | Notes |
|---|---|---|
| `tenant_id` | UUID PK | `gen_random_uuid()` |
| `slug` | VARCHAR(63) UNIQUE | kebab-case tenant identifier |
| `name` | VARCHAR(255) | Display name |
| `contact_email` | VARCHAR(255) | |
| `fid` | VARCHAR(255) UNIQUE | OAuth2 client ID assigned at onboarding |
| `status` | VARCHAR(20) | ACTIVE, DELETED |
| `max_pipelines` | INT | DEFAULT 10 |
| `max_total_parallelism` | INT | DEFAULT 50 |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

### V2: `pipelines`, `pipeline_sources`, `pipeline_sinks`
**pipelines:** `pipeline_id` (PK), `tenant_id` (FK), `name`, `description`, `sql_query`, `status` (DRAFT/DEPLOYING/RUNNING/SUSPENDED/FAILED/DELETED), `parallelism` (DEFAULT 1), `checkpoint_interval_ms` (DEFAULT 60000), `upgrade_mode` (SAVEPOINT/LAST_STATE/STATELESS)

**pipeline_sources:** `source_id` (PK), `pipeline_id` (FK cascade delete), `table_name`, `topic`, `bootstrap_servers`, `consumer_group`, `startup_mode` (GROUP_OFFSETS/EARLIEST/LATEST), `schema_registry_url`, `avro_subject`, `watermark_column`, `watermark_delay_ms` (DEFAULT 5000), `extra_properties` (JSONB)

**pipeline_sinks:** `sink_id` (PK), `pipeline_id` (FK cascade delete), `table_name`, `topic`, `bootstrap_servers`, `schema_registry_url`, `avro_subject`, `partitioner` (DEFAULT/FIXED/ROUND_ROBIN), `delivery_guarantee` (AT_LEAST_ONCE/EXACTLY_ONCE/NONE)

### V3: `pipeline_deployments`
`pipeline_id` (PK = FK cascade delete), `version` (DEFAULT 1), `k8s_resource_name` (`pipeline-{uuid}`), `configmap_name` (`pipeline-sql-{uuid}`), `flink_job_id`, `lifecycle_state` (mirrors FlinkDeployment status), `job_state` (RUNNING/FAILED/SUSPENDED/FINISHED), `last_savepoint_path`, `error_message`, `last_synced_at`

## Gaps and What Needs to Be Built

### Must build from scratch:

1. **Backend Dockerfile** — Spring Boot fat-jar container. No Dockerfile exists for the backend.

2. **`docker-compose.yml`** — Needs services:
   - `postgres` (postgres:16-alpine) — port 5432
   - `keycloak` (quay.io/keycloak/keycloak) — port 8180 (avoid conflict with backend on 8080)
   - `zookeeper` + `kafka` (Confluent or Bitnami) — Kafka on port 9092
   - `schema-registry` (confluentinc/cp-schema-registry) — port 8081
   - `backend` (Spring Boot) — port 8080, depends on postgres + keycloak
   - `frontend` (nginx) — port 3000 or 80, depends on backend

3. **`application-local.yml`** (Spring profile) — local defaults:
   - `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` pointed at Docker Compose postgres
   - `OAUTH2_ISSUER_URI` pointed at local Keycloak realm
   - `oauth2.admin.url` set to local Keycloak (or left blank for NoOp mode)

4. **Keycloak realm configuration** — A realm import JSON that:
   - Creates the `flink-platform` realm
   - Creates a test user with a `tenant_id` custom claim mapper
   - Optionally creates an admin client for the oauth2.admin flow

5. **Seed data script** — SQL or HTTP-based script to:
   - Create a demo tenant row in the DB
   - Create a sample pipeline in DRAFT status
   - Must handle the K8s dependency (either via kind cluster or NoOp)

6. **Kind cluster setup script** (`scripts/kind-setup.sh`):
   - Creates a `kind` cluster
   - Installs Flink Kubernetes Operator (Helm chart)
   - Installs MinIO for S3-compatible state storage
   - Configures kubeconfig

7. **Makefile** — Convenience targets: `up`, `down`, `seed`, `kind-setup`, `logs`, `build`

8. **Getting-started documentation**

### Critical design decisions required:

**SSRF block for Schema Registry in Docker:** `SchemaRegistryValidationService` blocks `10.*`, `172.16-31.*`, `192.168.*`, `localhost`. Docker bridge IPs are in `172.17-20.x.x` — these are blocked. Options:
  - Add a `local` profile that overrides/disables SSRF checks
  - Use `host.docker.internal` or a non-private hostname approach
  - Use Docker's `--network=host` mode (Linux only)
  - Route schema-registry through a public hostname with DNS

**TenantNamespaceProvisioner has no NoOp:** Tenant creation calls K8s unconditionally. A `local` profile must either:
  - Point `~/.kube/config` at a real kind cluster, OR
  - Add a `NoOpTenantNamespaceProvisioner` bean activated by local profile

**JWT required for authenticated APIs:** All endpoints except `POST /api/v1/tenants` require a valid JWT with `tenant_id` claim. Keycloak must be running and configured, or the local profile must provide a mock `JwtDecoder` similar to `TestSecurityConfig`.
