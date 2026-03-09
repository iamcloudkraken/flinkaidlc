---
workflow: adversarial
git:
  change_strategy: unit
  auto_merge: true
  auto_squash: false
announcements: [changelog, release-notes, social-posts, blog-draft]
created: 2026-03-08
status: active
epic: ""
---

# Flink SQL Pipeline Platform

## Problem
Teams building real-time analytics and operational data processing have no self-service way to define Kafka-to-Kafka transformation pipelines. Setting up Apache Flink jobs requires deep infrastructure knowledge — writing Java/Scala, packaging JARs, configuring Kubernetes. Clients need to express pipelines in SQL and have the platform handle all deployment complexity.

## Solution
A multi-tenant platform that accepts Flink SQL pipeline definitions via REST API and Web UI, translates them into `FlinkDeployment` CRDs on Kubernetes (using the Flink Kubernetes Operator), and manages the full lifecycle. Each tenant gets isolated Kubernetes namespace resources. SQL is delivered to Flink jobs via ConfigMaps — no Docker build cycle per pipeline. Tenants authenticate using OAuth2 Functional IDs (FIDs).

## Domain Model

### Entities

- **Tenant** — A registered platform user (org or team). Key fields: `tenant_id` (UUID), `slug` (K8s namespace suffix), `name`, `contact_email`, `fid` (OAuth2 client ID), `status` (ACTIVE/SUSPENDED/DELETED), `max_pipelines`, `max_total_parallelism`, `created_at`

- **Pipeline** — A SQL-defined Kafka→Kafka data transformation. Key fields: `pipeline_id` (UUID), `tenant_id` (FK), `name`, `description`, `sql_query`, `status` (DRAFT/DEPLOYING/RUNNING/SUSPENDED/FAILED/DELETED), `parallelism`, `checkpoint_interval_ms`, `upgrade_mode` (SAVEPOINT/LAST_STATE/STATELESS), `created_at`, `updated_at`

- **PipelineSource** — A Kafka source table for a pipeline. Key fields: `source_id` (UUID), `pipeline_id` (FK), `table_name` (Flink DDL alias), `topic`, `bootstrap_servers`, `consumer_group`, `startup_mode` (GROUP_OFFSETS/EARLIEST/LATEST), `schema_registry_url`, `avro_subject`, `watermark_column`, `watermark_delay_ms`, `extra_properties` (JSONB)

- **PipelineSink** — A Kafka sink table for a pipeline. Key fields: `sink_id` (UUID), `pipeline_id` (FK), `table_name`, `topic`, `bootstrap_servers`, `schema_registry_url`, `avro_subject`, `partitioner` (DEFAULT/FIXED/ROUND_ROBIN), `delivery_guarantee` (AT_LEAST_ONCE/EXACTLY_ONCE)

- **PipelineDeployment** — Sync state between the platform DB and the live K8s `FlinkDeployment`. Key fields: `pipeline_id` (FK), `version` (INT), `k8s_resource_name`, `configmap_name`, `flink_job_id`, `lifecycle_state` (from FlinkDeployment status), `job_state` (RUNNING/FAILED/SUSPENDED), `last_savepoint_path`, `error_message`, `last_synced_at`

### Relationships
- Tenant has many Pipelines (isolated per K8s namespace `tenant-<slug>`)
- Pipeline has one-or-more PipelineSources, one-or-more PipelineSinks, one PipelineDeployment
- PipelineDeployment maps 1:1 to a `FlinkDeployment` CRD in K8s

### Data Sources
- **Flink Kubernetes Operator** (K8s CRD `flink.apache.org/v1beta1/FlinkDeployment`): Lifecycle management — create/patch/delete via Fabric8 `genericKubernetesResources`
- **Kubernetes API** (Fabric8 client): Namespace, ServiceAccount, Role, RoleBinding, ResourceQuota, NetworkPolicy, ConfigMap
- **Kafka** (source + sink): Accessed via Flink SQL connector — clients specify bootstrap servers and topics
- **Confluent Schema Registry**: Avro schema validation — all sources and sinks use `avro-confluent` format
- **PostgreSQL** (platform DB): All pipeline and tenant metadata, deployment sync state
- **OAuth2 Provider** (e.g. Keycloak): Issues JWT tokens per FID — `tenant_id` resolved from token claims

### Data Gaps
- Docker base image (containing SQL Runner JAR) must be pre-built and available in a registry — platform does not own the image build pipeline. Builders should assume `flink-sql-runner:latest` exists at a configurable registry path.
- Kafka bootstrap servers are client-provided and can be arbitrary endpoints — platform does not validate reachability before deployment.

## Success Criteria
- [ ] `POST /api/v1/tenants` creates tenant record, provisions K8s namespace with ServiceAccount/Role/RoleBinding/ResourceQuota/NetworkPolicy, registers FID as OAuth2 client — returns `201` with tenant ID and FID credentials
- [ ] FID exchange: tenant uses FID + secret to obtain JWT with `tenant_id` claim via OAuth2 client credentials flow
- [ ] `GET /api/v1/tenants/{id}` returns tenant metadata and current resource usage (pipeline count, total parallelism in use)
- [ ] `PUT /api/v1/tenants/{id}` updates metadata and resource limits — K8s ResourceQuota patched accordingly
- [ ] `DELETE /api/v1/tenants/{id}` suspends all pipelines, removes all K8s namespace resources, marks tenant DELETED
- [ ] Resource limits enforced — `POST /api/v1/pipelines` returns `429` if tenant exceeds max pipelines or max total parallelism
- [ ] Tenant onboarding UI: self-service registration form displaying generated FID and secret on creation
- [ ] Tenant dashboard shows resource usage vs. limits
- [ ] `POST /api/v1/pipelines` accepts full spec (SQL, sources, sinks, parallelism, checkpoint interval), creates `FlinkDeployment` CRD in tenant namespace — returns `201` with pipeline ID
- [ ] `GET /api/v1/pipelines/{id}` returns status reflecting live `FlinkDeployment` state (DEPLOYING/RUNNING/SUSPENDED/FAILED)
- [ ] `PUT /api/v1/pipelines/{id}` triggers savepoint-based upgrade — no data loss
- [ ] `DELETE /api/v1/pipelines/{id}` stops job, triggers savepoint, removes CRD
- [ ] `POST /api/v1/pipelines/{id}/suspend` and `/resume` transition state via CRD patch
- [ ] Tenant isolation enforced — cross-tenant access returns `403`
- [ ] JWT resolves `tenant_id` per request — unauthenticated returns `401`
- [ ] SQL validated before deployment — DDL statements rejected `400`, unknown table refs return descriptive error
- [ ] Avro + Schema Registry enforced — invalid schema URL or missing subject returns descriptive error before deployment
- [ ] Checkpoints written to `s3://flink-state/<tenant-id>/<pipeline-id>/checkpoints`
- [ ] All tests pass, service layer coverage ≥ 80%
- [ ] All API errors use `application/problem+json` (RFC 7807) format
- [ ] SQL editor with syntax highlighting in Web UI
- [ ] Pipeline list with live status, pipeline detail view, create/edit form, suspend/resume/delete actions
- [ ] Web UI authenticates via JWT/OAuth2, deployed as separate K8s service (React + TypeScript)

## Context
- Flink SQL jobs cannot run `.sql` files natively — SQL is delivered via Kubernetes ConfigMap mounted into pods running the SQL Runner JAR (`local:///opt/flink/usrlib/sql-runner.jar`). No Docker build per pipeline.
- Each pipeline maps to one `FlinkDeployment` in Application Mode in the tenant's namespace.
- Lifecycle operations (suspend/resume/scale/upgrade) are expressed as CRD spec patches — the Flink Kubernetes Operator reconciles desired state.
- Fabric8 `kubernetes-client` is used for all K8s operations from Spring Boot.
- The Flink Kubernetes Operator is pre-installed cluster-scoped — platform only submits CRDs.
- State backend: RocksDB with incremental checkpointing to S3/MinIO.
- Kafka format: Avro with Confluent Schema Registry only (no raw JSON).
- Multi-tenancy: K8s namespace isolation per tenant — `tenant-<slug>` namespace contains all Flink jobs for that tenant.
