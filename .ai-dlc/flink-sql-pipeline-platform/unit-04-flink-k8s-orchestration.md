---
status: pending
depends_on: [unit-01-data-model-and-foundation]
branch: ai-dlc/flink-sql-pipeline-platform/04-flink-k8s-orchestration
discipline: backend
workflow: adversarial
ticket: ""
---

# unit-04-flink-k8s-orchestration

## Description
Implement `FlinkOrchestrationService` — the layer that translates pipeline specs into Kubernetes resources. Responsibilities: generate Flink SQL statements from pipeline domain model, deliver SQL via ConfigMap, create/patch/delete `FlinkDeployment` CRDs, and run a status-sync informer that keeps `PipelineDeployment` table in sync with live K8s state.

## Discipline
backend — adversarial workflow due to direct K8s API access and multi-tenant namespace operations.

## Domain Entities
**PipelineDeployment** — primary write target. **Pipeline**, **PipelineSource**, **PipelineSink** — read-only inputs to CRD generation.

## Data Sources
- **Kubernetes API** (Fabric8 `KubernetesClient` from unit-01): ConfigMap CRUD, FlinkDeployment CRD CRUD via `genericKubernetesResources`
- **PostgreSQL** (via `PipelineDeploymentRepository`, `PipelineRepository` from unit-01): write sync state
- **Flink Kubernetes Operator** (cluster-scoped, pre-installed): reconciles `FlinkDeployment` CRDs

## Technical Specification

### SQL Generation

`FlinkSqlGenerator` under `com.flinkaidlc.platform.orchestration`:

Generates a complete `statements.sql` file from a `Pipeline` + its sources + sinks:

```sql
-- Source table DDL (one per PipelineSource)
CREATE TABLE {source.tableName} (
  -- Avro schema fields inferred from Schema Registry subject (or use LIKE for dynamic)
  -- For v1: use a flexible ROW type with the watermark if specified
  `data` STRING,
  `event_time` TIMESTAMP(3),
  WATERMARK FOR `event_time` AS `event_time` - INTERVAL '{source.watermarkDelayMs}' MILLISECOND
) WITH (
  'connector' = 'kafka',
  'topic' = '{source.topic}',
  'properties.bootstrap.servers' = '{source.bootstrapServers}',
  'properties.group.id' = '{source.consumerGroup}',
  'scan.startup.mode' = '{source.startupMode.toFlinkValue()}',
  'format' = 'avro-confluent',
  'avro-confluent.url' = '{source.schemaRegistryUrl}',
  'avro-confluent.subject' = '{source.avroSubject}'
);

-- Sink table DDL (one per PipelineSink)
CREATE TABLE {sink.tableName} (
  `data` STRING
) WITH (
  'connector' = 'kafka',
  'topic' = '{sink.topic}',
  'properties.bootstrap.servers' = '{sink.bootstrapServers}',
  'format' = 'avro-confluent',
  'avro-confluent.url' = '{sink.schemaRegistryUrl}',
  'avro-confluent.subject' = '{sink.avroSubject}',
  'sink.partitioner' = '{sink.partitioner.toFlinkValue()}',
  'sink.delivery-guarantee' = '{sink.deliveryGuarantee.toFlinkValue()}'
);

-- Client SQL (the pipeline.sqlQuery, passed through as-is)
{pipeline.sqlQuery}
```

**Note on schema**: For v1, use `LIKE` or a generic `STRING` type. Full Avro schema inference from Schema Registry (fetching the schema and generating DDL column types) is a v2 enhancement — document this explicitly.

### ConfigMap Delivery

For each pipeline, create a ConfigMap in `tenant-<slug>` namespace:
- Name: `pipeline-sql-{pipelineId}`
- Data key: `statements.sql` → generated SQL content
- Label: `app.kubernetes.io/managed-by: flink-platform`, `pipeline-id: {pipelineId}`

On upgrade: update ConfigMap data and increment `PipelineDeployment.version`.
On delete: delete ConfigMap.

### FlinkDeployment CRD Generation

`FlinkDeploymentBuilder` generates the `FlinkDeployment` YAML structure as a `Map<String, Object>`:

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: pipeline-{pipelineId}
  namespace: tenant-{tenantSlug}
  labels:
    app.kubernetes.io/managed-by: flink-platform
    tenant-id: {tenantId}
    pipeline-id: {pipelineId}
spec:
  image: ${FLINK_SQL_RUNNER_IMAGE}
  flinkVersion: v1_20
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "1"
    state.backend: rocksdb
    state.backend.incremental: "true"
    state.checkpoints.dir: s3://${FLINK_STATE_S3_BUCKET}/{tenantId}/{pipelineId}/checkpoints
    state.savepoints.dir: s3://${FLINK_STATE_S3_BUCKET}/{tenantId}/{pipelineId}/savepoints
    execution.checkpointing.interval: "{pipeline.checkpointIntervalMs}ms"
    restart-strategy: fixed-delay
    restart-strategy.fixed-delay.attempts: "3"
  serviceAccount: flink
  jobManager:
    resource:
      memory: "1024m"
      cpu: 0.5
  taskManager:
    resource:
      memory: "1024m"
      cpu: 1.0
  job:
    jarURI: local:///opt/flink/usrlib/sql-runner.jar
    args: ["/opt/flink/usrlib/sql-scripts/statements.sql"]
    parallelism: {pipeline.parallelism}
    upgradeMode: {pipeline.upgradeMode.toFlinkValue()}
    state: running
  podTemplate:
    spec:
      volumes:
        - name: sql-scripts
          configMap:
            name: pipeline-sql-{pipelineId}
      containers:
        - name: flink-main-container
          volumeMounts:
            - name: sql-scripts
              mountPath: /opt/flink/usrlib/sql-scripts
```

### FlinkOrchestrationService Implementation

`FlinkOrchestrationServiceImpl` implements the interface from unit-03:

- **`deploy(Pipeline pipeline)`**:
  1. Generate SQL → create ConfigMap in tenant namespace
  2. Generate FlinkDeployment spec → create CRD via `genericKubernetesResources`
  3. Upsert `PipelineDeployment` record with `k8s_resource_name`, `configmap_name`, `lifecycle_state = DEPLOYED`

- **`upgrade(Pipeline pipeline)`**:
  1. Regenerate SQL → update ConfigMap
  2. Patch FlinkDeployment: update parallelism, upgradeMode, increment `restartNonce` if SQL-only change, or trigger `savepointTriggerNonce` for state-preserving upgrade
  3. Increment `PipelineDeployment.version`

- **`suspend(Pipeline pipeline)`**:
  1. Patch FlinkDeployment: `spec.job.state = suspended`
  2. Update `PipelineDeployment.job_state = SUSPENDED`

- **`resume(Pipeline pipeline)`**:
  1. Patch FlinkDeployment: `spec.job.state = running`

- **`teardown(Pipeline pipeline)`**:
  1. Patch FlinkDeployment: `spec.job.state = suspended` (triggers savepoint)
  2. Wait up to 60s for savepoint path to appear in FlinkDeployment status
  3. Store savepoint path in `PipelineDeployment.last_savepoint_path`
  4. Delete FlinkDeployment CRD
  5. Delete ConfigMap

- **`suspendAll(UUID tenantId)`**:
  1. List all FlinkDeployments in `tenant-<slug>` namespace with label `tenant-id={tenantId}`
  2. Patch each: `spec.job.state = suspended`

### Status Sync

`FlinkDeploymentStatusSyncer` under `com.flinkaidlc.platform.orchestration`:

1. **Informer**: Use Fabric8 `SharedIndexInformer<GenericKubernetesResource>` to watch FlinkDeployments across all tenant namespaces (label selector: `app.kubernetes.io/managed-by=flink-platform`)
   - On `MODIFIED` event: extract `status.lifecycleState`, `status.jobStatus.state`, `status.jobStatus.savepointInfo.lastSavepoint.location`, `status.error`
   - Update `PipelineDeployment` and `Pipeline.status` accordingly:
     - `RUNNING` job → Pipeline.status = RUNNING
     - `FAILED` → Pipeline.status = FAILED, store error_message
     - `SUSPENDED` → Pipeline.status = SUSPENDED

2. **Fallback poller**: `@Scheduled(fixedDelayString = "${flink.sync.poll-interval-ms:30000}")` — list all FlinkDeployments with platform label, sync any that the informer may have missed

State mapping table:
| FlinkDeployment lifecycleState | jobStatus.state | Pipeline status |
|---|---|---|
| DEPLOYED, UPGRADING | RUNNING | RUNNING |
| DEPLOYED | FAILED | FAILED |
| SUSPENDED | SUSPENDED | SUSPENDED |
| FAILED (operator level) | any | FAILED |

## Success Criteria
- [ ] `deploy()` creates ConfigMap with valid `statements.sql` in tenant namespace and creates `FlinkDeployment` CRD — verified against a kind cluster
- [ ] Generated `statements.sql` includes source DDL, sink DDL, and the client SQL query for a pipeline with 2 sources and 1 sink
- [ ] `suspend()` patches FlinkDeployment `spec.job.state = suspended`; `resume()` patches to `running`
- [ ] `teardown()` triggers savepoint, stores path in `PipelineDeployment.last_savepoint_path`, deletes CRD and ConfigMap
- [ ] `suspendAll(tenantId)` suspends all FlinkDeployments in the tenant namespace
- [ ] Status informer updates `Pipeline.status` to RUNNING when FlinkDeployment job state = RUNNING
- [ ] Status informer updates `Pipeline.status` to FAILED with error message when job fails
- [ ] Fallback poller runs every 30s and syncs status for any missed events
- [ ] Integration test: deploy a mock FlinkDeployment to kind, verify status sync updates DB within 60s

## Risks
- **CRD schema changes between Flink Operator versions**: `FlinkDeployment` spec fields may differ between operator versions. Mitigation: pin operator version in cluster setup docs, test against that version only.
- **Savepoint timeout on teardown**: waiting 60s for savepoint may block the HTTP request. Mitigation: make `teardown()` async — start teardown, return immediately; status sync will confirm completion.
- **Cross-tenant CRD access**: the Fabric8 client has cluster-wide access — a bug could accidentally list/modify CRDs in the wrong namespace. Mitigation: always scope K8s calls with explicit namespace from tenant slug; add unit tests asserting namespace is always set.
- **ConfigMap name collision**: two pipelines with similar IDs could theoretically collide. Mitigation: use full UUID in ConfigMap name (no truncation).
- **Informer disconnects**: SharedIndexInformer can disconnect under network instability. Mitigation: implement reconnect logic with exponential backoff; fallback poller ensures eventual consistency.

## Boundaries
This unit does NOT implement REST endpoints (unit-03). It does NOT implement tenant namespace provisioning (unit-02). It does NOT implement the frontend (unit-05). The Flink Kubernetes Operator is assumed pre-installed — this unit never installs or manages the operator itself.

## Notes
- Use `client.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment").inNamespace(namespace)` for all CRD operations
- Build the FlinkDeployment as `new GenericKubernetesResourceBuilder()...` or construct as raw `Map<String,Object>` and use `client.resource(...).createOrReplace()`
- All K8s API calls should be wrapped in try-catch with specific error handling for `KubernetesClientException` (400, 403, 404, 409, 503)
- `@Async` methods require `@EnableAsync` on the Spring Boot application class
