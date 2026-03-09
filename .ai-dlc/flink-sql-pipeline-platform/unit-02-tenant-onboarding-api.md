---
status: completed
depends_on: [unit-01-data-model-and-foundation]
branch: ai-dlc/flink-sql-pipeline-platform/02-tenant-onboarding-api
discipline: backend
workflow: ""
ticket: ""
---

# unit-02-tenant-onboarding-api

## Description
Implement self-service tenant onboarding: REST CRUD for Tenant entities, Kubernetes namespace provisioning (ServiceAccount, Role, RoleBinding, ResourceQuota, NetworkPolicy), and OAuth2 FID registration. A new tenant POSTs their details, receives a FID + client secret, and their isolated K8s namespace is ready for Flink jobs.

## Discipline
backend — executed by backend-focused agents.

## Domain Entities
**Tenant** — `tenant_id`, `slug`, `name`, `contact_email`, `fid`, `status`, `max_pipelines`, `max_total_parallelism`

## Data Sources
- **PostgreSQL** (via `TenantRepository` from unit-01): persist/read Tenant records
- **Kubernetes API** (via `KubernetesClient` bean from unit-01): create/delete Namespace, ServiceAccount, Role, RoleBinding, ResourceQuota, NetworkPolicy
- **OAuth2 Provider** (e.g. Keycloak Admin REST API or equivalent): register FID as an OAuth2 client credentials client — endpoint: `POST /admin/realms/{realm}/clients`

## Technical Specification

### REST Endpoints

`POST /api/v1/tenants` — unauthenticated (self-service)
- Request body:
  ```json
  {
    "name": "Acme Analytics",
    "slug": "acme",
    "contactEmail": "admin@acme.com",
    "maxPipelines": 10,
    "maxTotalParallelism": 50
  }
  ```
- Validation: `slug` must match `^[a-z0-9-]{3,63}$`, must be unique, `maxPipelines` ≥ 1, `maxTotalParallelism` ≥ 1
- On success:
  1. Persist Tenant record with generated `tenant_id` and `fid` (UUID)
  2. Register FID as OAuth2 client (client credentials grant) with the OAuth2 provider
  3. Provision K8s namespace `tenant-<slug>` with all required resources (see below)
  4. Return `201`:
     ```json
     {
       "tenantId": "...",
       "slug": "acme",
       "fid": "...",
       "fidSecret": "...",  // shown ONCE, not stored in plaintext
       "namespaceProvisioned": true
     }
     ```
- On failure: roll back DB record if K8s or OAuth2 provisioning fails (use `@Transactional` + compensating deletes)

`GET /api/v1/tenants/{tenantId}` — authenticated, tenant-scoped
- JWT `tenant_id` claim must match path `tenantId` — else `403`
- Returns tenant metadata + resource usage:
  ```json
  {
    "tenantId": "...",
    "slug": "acme",
    "name": "Acme Analytics",
    "contactEmail": "admin@acme.com",
    "status": "ACTIVE",
    "maxPipelines": 10,
    "maxTotalParallelism": 50,
    "usedPipelines": 3,
    "usedParallelism": 12
  }
  ```

`PUT /api/v1/tenants/{tenantId}` — authenticated, tenant-scoped
- Updatable fields: `name`, `contactEmail`, `maxPipelines`, `maxTotalParallelism`
- On `maxPipelines` / `maxTotalParallelism` change: patch K8s `ResourceQuota` in namespace
- Returns updated tenant

`DELETE /api/v1/tenants/{tenantId}` — authenticated, tenant-scoped
- Suspend all RUNNING pipelines (delegate to `PipelineService.suspendAll(tenantId)` — stub OK in this unit)
- Delete K8s namespace `tenant-<slug>` (cascades all K8s resources)
- Mark Tenant status = DELETED in DB
- Return `204`

### Kubernetes Namespace Provisioning

On `POST /api/v1/tenants`, provision in order using the `KubernetesClient` bean:

1. **Namespace**: `tenant-<slug>`, label `app.kubernetes.io/managed-by: flink-platform`
2. **ServiceAccount**: name `flink`, namespace `tenant-<slug>`
3. **Role**: name `flink`, namespace `tenant-<slug>` — rules:
   - `apiGroups: [""]`, resources: `["pods","configmaps","services","endpoints"]`, verbs: `["get","list","watch","create","update","delete"]`
   - `apiGroups: ["apps"]`, resources: `["deployments","replicasets"]`, verbs: `["get","list","watch","create","update","delete"]`
4. **RoleBinding**: binds `flink` Role to `flink` ServiceAccount in `tenant-<slug>`
5. **ResourceQuota**: name `tenant-quota`, namespace `tenant-<slug>`:
   ```yaml
   hard:
     pods: "<maxPipelines * 4>"         # 2 JM + 2 TM per pipeline approx
     requests.cpu: "<maxTotalParallelism>"
     requests.memory: "<maxTotalParallelism * 2>Gi"
   ```
6. **NetworkPolicy**: name `tenant-isolation`, namespace `tenant-<slug>`:
   - Deny all ingress from other namespaces
   - Allow ingress only within same namespace and from platform namespace

### OAuth2 FID Registration

Implement `OAuth2ClientRegistrationService` that calls the OAuth2 provider Admin API:
- Config: `oauth2.admin.url`, `oauth2.admin.realm`, `oauth2.admin.client-id`, `oauth2.admin.client-secret` in `application.yml`
- `POST /admin/realms/{realm}/clients` with `clientId = fid`, `secret = generated UUID`, `serviceAccountsEnabled: true`, grant type `client_credentials`
- Return the generated secret (shown once to the caller, never stored in plaintext in DB)
- Stub interface `OAuth2ProviderClient` with a Keycloak implementation — allows swapping providers

### Service Layer
`TenantService` under `com.flinkaidlc.platform.tenant`:
- `onboardTenant(OnboardTenantRequest)` — orchestrates DB + K8s + OAuth2, with compensating deletes on failure
- `getTenantWithUsage(UUID tenantId)` — fetches tenant + counts active pipelines + sums parallelism
- `updateTenant(UUID tenantId, UpdateTenantRequest)` — updates DB + patches K8s ResourceQuota
- `deleteTenant(UUID tenantId)` — suspends pipelines + deletes K8s namespace + updates DB

### Error Cases
- `slug` already taken → `400` with `detail: "Slug 'acme' is already in use"`
- K8s namespace already exists → treat as conflict, attempt cleanup and return `409`
- OAuth2 provider unavailable → `503` with problem+json
- Tenant not found → `404`
- Cross-tenant access → `403`

## Success Criteria
- [ ] `POST /api/v1/tenants` with valid payload creates Tenant in DB, provisions K8s namespace with all 5 resource types (Namespace, ServiceAccount, Role, RoleBinding, ResourceQuota, NetworkPolicy), registers FID with OAuth2 provider — returns `201` with `fid` and `fidSecret`
- [ ] Duplicate `slug` returns `400` problem+json
- [ ] K8s provisioning failure rolls back DB record — no orphaned tenant records
- [ ] `GET /api/v1/tenants/{tenantId}` returns correct `usedPipelines` and `usedParallelism` counts
- [ ] `PUT /api/v1/tenants/{tenantId}` with updated `maxPipelines` patches K8s ResourceQuota
- [ ] `DELETE /api/v1/tenants/{tenantId}` removes K8s namespace and marks tenant DELETED
- [ ] Cross-tenant GET/PUT/DELETE returns `403`
- [ ] Integration test provisions a real namespace against a K8s test cluster (or kind) and verifies all 5 resources exist

## Risks
- **OAuth2 provider coupling**: Keycloak Admin API calls are provider-specific. Mitigation: `OAuth2ProviderClient` interface with a mock implementation for testing and Keycloak implementation for production.
- **Compensating transaction complexity**: If OAuth2 registration succeeds but K8s fails, the FID is orphaned in the OAuth2 provider. Mitigation: log the orphan and expose a `/api/v1/admin/tenants/{id}/reprovision` endpoint for manual recovery.
- **K8s namespace deletion latency**: Namespace deletion is async in K8s — the API returns immediately but resources are cleaned up by the garbage collector. Mitigation: mark tenant DELETED in DB immediately; do not wait for namespace to fully terminate.
- **ResourceQuota formula**: Pods-per-pipeline estimate (4) may not match actual Flink Operator pod counts. Mitigation: make the multiplier configurable via `flink.pods-per-pipeline` property.

## Boundaries
This unit does NOT implement pipeline CRUD (unit-03). It does NOT create `FlinkDeployment` CRDs (unit-04). The `suspendAll(tenantId)` call in `deleteTenant` may be stubbed as a no-op in this unit and implemented fully in unit-03.

## Notes
- Use `@Transactional` on `TenantService.onboardTenant` — catch K8s/OAuth2 exceptions, perform compensating deletes, then rethrow as `TenantProvisioningException` mapped to `503`
- K8s `ResourceQuota` CPU/memory values should be reasonable defaults; make multipliers configurable
- The `fidSecret` must never be stored in the DB — the OAuth2 provider owns the secret
