---
status: success
---

## Domain Model Summary

### Core Entities

**Tenant**
- `tenant_id` UUID PK
- `slug` VARCHAR UNIQUE — used as Kubernetes namespace suffix (`tenant-<slug>`)
- `name`, `status` (ACTIVE, SUSPENDED, DELETED), `created_at`

**Pipeline**
- `pipeline_id` UUID PK
- `tenant_id` FK
- `name`, `description`, `sql_query` (user's SELECT/INSERT SQL)
- `status` ENUM (DRAFT, DEPLOYING, RUNNING, SUSPENDED, FAILED, DELETED)
- `parallelism`, `checkpoint_interval_ms`, `upgrade_mode` (SAVEPOINT, LAST_STATE, STATELESS)

**PipelineSource** (Kafka source config)
- `source_id` FK → pipeline
- `table_name` (DDL alias), `topic`, `bootstrap_servers`, `consumer_group`
- `startup_mode` (GROUP_OFFSETS, EARLIEST, LATEST, TIMESTAMP, SPECIFIC)
- `format` (JSON, AVRO, AVRO_CONFLUENT, CSV, RAW), `schema_registry_url`
- `watermark_column`, `watermark_delay_ms`, `extra_properties` JSONB

**PipelineSink** (Kafka sink config)
- `sink_id` FK → pipeline
- `table_name`, `topic`, `bootstrap_servers`, `format`
- `partitioner` (DEFAULT, FIXED, ROUND_ROBIN)
- `delivery_guarantee` (AT_LEAST_ONCE, EXACTLY_ONCE)

**PipelineDeployment** (K8s sync state)
- `pipeline_id` FK
- `version` INT, `k8s_resource_name`, `flink_job_id`
- `lifecycle_state` (from FlinkDeployment status), `job_state` (RUNNING, FAILED, SUSPENDED)
- `last_savepoint` path, `error_message`, `last_synced_at`

---

### Key Findings

**1. SQL Job Submission via SQL Runner + ConfigMap**
Flink cannot natively execute `.sql` files as jobs. The standard approach is a SQL Runner JAR (from the [flink-kubernetes-operator/examples](https://github.com/apache/flink-kubernetes-operator/tree/main/examples/flink-sql-runner-example)) that reads a `statements.sql` file and executes each statement via `TableEnvironment#executeSql`. For the REST API use case, the SQL script should be delivered via a Kubernetes ConfigMap mounted into the Flink pods — this avoids a Docker build/push cycle per pipeline creation or update.

**2. FlinkDeployment CRD — Application Mode Per Pipeline**
Each pipeline maps to one `FlinkDeployment` CRD in the tenant's namespace. Key fields:
- `spec.job.jarURI`: `local:///opt/flink/usrlib/sql-runner.jar`
- `spec.job.args`: `["/opt/flink/usrlib/sql-scripts/statements.sql"]`
- `spec.job.state`: `running` or `suspended` (controls lifecycle)
- `spec.job.upgradeMode`: `savepoint` recommended for production
- `spec.flinkConfiguration`: checkpointing, state backend, restart strategy
- `spec.flinkVersion`: recommend `v1_20`

**3. Lifecycle Operations via CRD Patching**
All pipeline lifecycle operations (suspend, resume, scale, upgrade) are expressed as patches to the `FlinkDeployment` spec — the operator reconciles the desired state:
- Suspend: patch `spec.job.state = suspended`
- Resume: patch `spec.job.state = running`
- Restart without spec change: patch `spec.job.restartNonce` to a new value
- Scale: patch `spec.job.parallelism`
- Trigger savepoint manually: patch `spec.job.savepointTriggerNonce`
- Delete: `DELETE` the `FlinkDeployment` resource

**4. Multi-Tenant Namespace Isolation**
Each tenant gets a dedicated Kubernetes namespace `tenant-<slug>`. The Spring Boot API must provision per namespace: ServiceAccount `flink`, Role `flink` (pod/configmap/deployment permissions), RoleBinding, ResourceQuota, and NetworkPolicy. The Flink Kubernetes Operator should be installed cluster-scoped so it can watch dynamically created namespaces — no operator reconfiguration needed for new tenants.

**5. Fabric8 Kubernetes Client for Spring Boot**
Fabric8 `kubernetes-client` is the recommended Java client. Use `client.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment")` for CRUD on the FlinkDeployment CRD. Use typed builders for Namespace, ServiceAccount, Role, RoleBinding, ConfigMap, and ResourceQuota. The client auto-configures from in-cluster service account when deployed inside K8s.

**6. Kafka Connector DDL Syntax**
Source tables use `'connector' = 'kafka'` with `scan.startup.mode`. Sink tables use `'connector' = 'kafka'` with `sink.delivery-guarantee`. For changelog/compacted topics, use `'connector' = 'upsert-kafka'` which requires a PRIMARY KEY in the DDL. Supported formats: JSON (default), Avro, avro-confluent (Schema Registry), CSV, Raw.

**7. Window Aggregations**
Flink SQL supports four window TVF types: TUMBLE (non-overlapping fixed), HOP (sliding/overlapping), SESSION (activity-gap based), CUMULATE (step-accumulating). All require `WATERMARK FOR event_time` on the source table for event-time processing. Window functions return `window_start`, `window_end`, `window_time` columns.

**8. SQL Validation at API Layer**
User-provided SQL must be validated before storage: reject DDL statements (CREATE TABLE, DROP, ALTER), validate that referenced table names match declared source/sink aliases, and consider using JSQLParser for pre-execution syntax validation.

**9. State Backend and Storage**
Production deployments require durable storage for checkpoints and savepoints (S3/MinIO recommended). RocksDB state backend with incremental checkpointing is the production-grade choice. The state path per pipeline should be namespaced: `s3://flink-state/<tenant-id>/<pipeline-id>/checkpoints`.

**10. Status Synchronization**
The REST API database must stay in sync with the actual K8s FlinkDeployment status. Implement a Kubernetes informer/watch via Fabric8 and a `@Scheduled` fallback to poll FlinkDeployment status and update the `PipelineDeployment` table.

---

### Open Questions

1. **Docker base image ownership**: Who builds and maintains the Flink base image containing the SQL runner JAR? Is there an existing registry, or does this platform own the image pipeline?

2. **Kafka bootstrap server configuration**: Is there a single Kafka cluster per environment, or can clients specify arbitrary Kafka endpoints? (Security implication: arbitrary endpoints could reach internal infrastructure)

3. **Schema Registry**: Is Confluent Schema Registry (Avro/Protobuf) required, or is JSON without a registry sufficient for the initial version?

4. **State storage**: Is S3 (or MinIO) available in the target cluster? Or will PVCs be used for checkpoint/savepoint storage?

5. **High Availability for JobManager**: Is HA (2 JobManager replicas + K8s HA service factory) required from day one, or can single-replica start?

6. **Authentication/Authorization model**: How do tenants authenticate to the REST API? JWT? API keys? OAuth2? This determines how `tenant_id` is resolved per request.

7. **Flink Operator installation**: Is the Flink Kubernetes Operator already installed in the target cluster, or does the platform need to manage its installation?

8. **Pipeline SQL complexity limits**: Should there be any restrictions on SQL complexity (e.g., no multi-source joins, no MATCH_RECOGNIZE) in the initial version?

9. **Multi-pipeline per tenant in same namespace**: Should multiple pipelines for the same tenant share a namespace (confirmed), and is there a maximum pipeline count per tenant enforced?

10. **Observability**: Is there a requirement for exposing Flink job metrics (throughput, lag, checkpoint duration) through the REST API, or is Prometheus/Grafana direct integration sufficient?
