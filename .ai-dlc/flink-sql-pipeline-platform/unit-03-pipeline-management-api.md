---
status: completed
depends_on: [unit-01-data-model-and-foundation, unit-02-tenant-onboarding-api]
branch: ai-dlc/flink-sql-pipeline-platform/03-pipeline-management-api
discipline: backend
workflow: adversarial
ticket: ""
---

# unit-03-pipeline-management-api

## Description
Implement the Pipeline REST API — full CRUD for pipelines including SQL validation, resource limit enforcement, and lifecycle operations (suspend/resume). This unit owns the REST layer and service orchestration; it delegates all Flink/K8s operations to the `FlinkOrchestrationService` from unit-04 (stub during development, integrated at merge).

## Discipline
backend — adversarial workflow due to SQL injection surface and multi-tenant isolation requirements.

## Domain Entities
**Pipeline**, **PipelineSource**, **PipelineSink**, **PipelineDeployment** — full lifecycle management from DRAFT through RUNNING/SUSPENDED/FAILED.

## Data Sources
- **PostgreSQL** (via `PipelineRepository`, `PipelineDeploymentRepository` from unit-01)
- **`TenantRepository`** (unit-01): resolve tenant + check resource limits
- **`FlinkOrchestrationService`** (unit-04): delegate CRD creation/deletion/patching — interface defined in this unit, implemented in unit-04

## Technical Specification

### REST Endpoints

All endpoints require JWT auth. `tenant_id` from JWT claim scopes all queries.

**`POST /api/v1/pipelines`**
- Request body:
  ```json
  {
    "name": "Fraud Detection Pipeline",
    "description": "...",
    "sqlQuery": "INSERT INTO fraud_alerts SELECT ...",
    "parallelism": 4,
    "checkpointIntervalMs": 30000,
    "upgradeMode": "SAVEPOINT",
    "sources": [
      {
        "tableName": "transactions",
        "topic": "payments.transactions",
        "bootstrapServers": "kafka:9092",
        "consumerGroup": "flink-fraud-cg",
        "startupMode": "GROUP_OFFSETS",
        "schemaRegistryUrl": "http://schema-registry:8081",
        "avroSubject": "transactions-value",
        "watermarkColumn": "event_time",
        "watermarkDelayMs": 5000
      }
    ],
    "sinks": [
      {
        "tableName": "fraud_alerts",
        "topic": "fraud.alerts",
        "bootstrapServers": "kafka:9092",
        "schemaRegistryUrl": "http://schema-registry:8081",
        "avroSubject": "fraud-alerts-value",
        "deliveryGuarantee": "AT_LEAST_ONCE"
      }
    ]
  }
  ```
- Validation (fail fast with `400` before any DB write):
  1. **Resource limits**: count active pipelines for tenant — if at `max_pipelines` → `429`
  2. **Parallelism limit**: sum parallelism of active pipelines + new parallelism — if exceeds `max_total_parallelism` → `429`
  3. **SQL validation**: parse `sqlQuery` using JSQLParser — reject if contains DDL keywords (`CREATE`, `DROP`, `ALTER`, `TRUNCATE`); reject if contains semicolons mid-query (multi-statement injection); validate that all `tableName` values in sources/sinks are referenced in the SQL
  4. **Schema Registry reachability**: for each source/sink, `GET {schemaRegistryUrl}/subjects/{avroSubject}/versions/latest` — if `404` or unreachable → `400` with descriptive error
- On success: persist Pipeline (status=DRAFT), sources, sinks; call `FlinkOrchestrationService.deploy(pipeline)` async; update status to DEPLOYING; return `201`

**`GET /api/v1/pipelines`**
- Returns paginated list of pipelines for the authenticated tenant
- Query params: `status`, `page`, `size`
- Each item includes: `pipelineId`, `name`, `status`, `parallelism`, `createdAt`, `updatedAt`

**`GET /api/v1/pipelines/{pipelineId}`**
- Full pipeline detail: includes sources, sinks, and deployment state (`lifecycleState`, `jobState`, `lastSavepointPath`, `errorMessage`)
- Tenant isolation: if pipeline.tenantId ≠ JWT tenant_id → `403`

**`PUT /api/v1/pipelines/{pipelineId}`**
- Updatable fields: `name`, `description`, `sqlQuery`, `parallelism`, `checkpointIntervalMs`, `upgradeMode`, `sources`, `sinks`
- Re-run all validations (SQL, schema registry, resource limits adjusted for current parallelism delta)
- Call `FlinkOrchestrationService.upgrade(pipeline)` — triggers savepoint + redeploy
- Return updated pipeline

**`DELETE /api/v1/pipelines/{pipelineId}`**
- Call `FlinkOrchestrationService.teardown(pipeline)` — triggers savepoint + CRD deletion
- Mark pipeline status = DELETED
- Return `204`

**`POST /api/v1/pipelines/{pipelineId}/suspend`**
- Call `FlinkOrchestrationService.suspend(pipeline)`
- Update status = SUSPENDED
- Return `200` with updated pipeline

**`POST /api/v1/pipelines/{pipelineId}/resume`**
- Call `FlinkOrchestrationService.resume(pipeline)`
- Update status = DEPLOYING (then RUNNING once K8s confirms)
- Return `200` with updated pipeline

### SQL Validation Service
`SqlValidationService` under `com.flinkaidlc.platform.pipeline`:
- Dependency: `net.sf.jsqlparser:jsqlparser`
- `validate(String sql, List<String> declaredTableNames)`:
  - Parse SQL — catch `JSQLParserException` → `400`
  - Walk AST for DDL statements — if found → throw `SqlValidationException("DDL statements are not permitted")`
  - Extract all table references from the SELECT — verify each is in `declaredTableNames` (case-insensitive) — unknown refs → `400` with list of unknown names

### Schema Registry Validation Service
`SchemaRegistryValidationService` under `com.flinkaidlc.platform.pipeline`:
- Uses `RestTemplate` / `WebClient` to call `GET {schemaRegistryUrl}/subjects/{avroSubject}/versions/latest`
- Timeout: 5s — if unreachable → `400 "Schema Registry at {url} is not reachable"`
- If subject not found (404) → `400 "Avro subject '{subject}' not found in registry at {url}"`

### FlinkOrchestrationService Interface
```java
public interface FlinkOrchestrationService {
    void deploy(Pipeline pipeline);
    void upgrade(Pipeline pipeline);
    void suspend(Pipeline pipeline);
    void resume(Pipeline pipeline);
    void teardown(Pipeline pipeline);
    void suspendAll(UUID tenantId);  // called by TenantService.deleteTenant
}
```
- Stub implementation `NoOpFlinkOrchestrationService` for this unit's tests
- Real implementation in unit-04

### Service Layer
`PipelineService` under `com.flinkaidlc.platform.pipeline`:
- All public methods `@Transactional`
- Injected: `PipelineRepository`, `TenantRepository`, `SqlValidationService`, `SchemaRegistryValidationService`, `FlinkOrchestrationService`
- `createPipeline(UUID tenantId, CreatePipelineRequest)`: validate → persist → deploy async
- `updatePipeline(UUID tenantId, UUID pipelineId, UpdatePipelineRequest)`: validate → persist → upgrade
- `deletePipeline(UUID tenantId, UUID pipelineId)`: teardown → mark DELETED
- `suspendPipeline(UUID tenantId, UUID pipelineId)`: suspend → update status
- `resumePipeline(UUID tenantId, UUID pipelineId)`: resume → update status

## Success Criteria
- [ ] `POST /api/v1/pipelines` with valid spec creates pipeline and sources/sinks in DB, calls `FlinkOrchestrationService.deploy`, returns `201`
- [ ] SQL containing `CREATE TABLE`, `DROP`, `ALTER`, or mid-query semicolon is rejected with `400` problem+json
- [ ] SQL referencing an undeclared table name returns `400` with the unknown table name in `detail`
- [ ] Avro subject not found in Schema Registry returns `400` with descriptive error
- [ ] Schema Registry unreachable returns `400` (not `503`) — fail the pipeline creation, not the platform
- [ ] `POST /api/v1/pipelines` when tenant is at `max_pipelines` returns `429`
- [ ] `POST /api/v1/pipelines` when parallelism would exceed `max_total_parallelism` returns `429`
- [ ] `GET /api/v1/pipelines/{pipelineId}` for another tenant's pipeline returns `403`
- [ ] `DELETE /api/v1/pipelines/{pipelineId}` calls `teardown` and marks pipeline DELETED
- [ ] `POST /api/v1/pipelines/{pipelineId}/suspend` and `/resume` call the correct orchestration methods

## Risks
- **SQL injection via Flink DDL**: clients could submit `CREATE TABLE` with malicious properties or try to escape the SQL context. Mitigation: JSQLParser rejects DDL at parse time; consider also a blocklist of dangerous keywords (`EXECUTE`, `LOAD`).
- **Schema Registry URL SSRF**: clients could provide an internal URL (e.g. `http://metadata.internal`) to probe internal services. Mitigation: validate schema registry URLs against an allowlist pattern or CIDR blocklist; log all outbound schema registry calls.
- **Async deploy race condition**: status is set to DEPLOYING before `FlinkOrchestrationService.deploy` completes. If the service call fails synchronously, the pipeline is stuck in DEPLOYING. Mitigation: wrap deploy call in try-catch; on exception, set status = FAILED with error message.
- **Tenant parallelism count drift**: parallelism sum is calculated at request time from DB; if two concurrent creates race, both could pass the limit check. Mitigation: add a DB-level check constraint or use `SELECT FOR UPDATE` on tenant row during create.

## Boundaries
This unit does NOT implement the `FlinkOrchestrationService` (unit-04). It does NOT provision K8s namespaces (unit-02). It does NOT implement the frontend (unit-05). The `suspendAll` method stub will be completed when unit-04 is merged.

## Notes
- `FlinkOrchestrationService.deploy()` should be called asynchronously (`@Async`) — the REST response should not wait for K8s to confirm RUNNING state
- Pipeline status transitions: DRAFT → DEPLOYING (on deploy) → RUNNING (on K8s confirm, via unit-04 sync) → SUSPENDED (on suspend) → DELETED (on delete)
- Use `@JsonProperty` or MapStruct for request/response DTOs — keep entities out of the controller layer
