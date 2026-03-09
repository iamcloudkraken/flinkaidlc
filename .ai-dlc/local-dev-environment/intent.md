---
workflow: default
git:
  change_strategy: intent
  auto_merge: true
  auto_squash: false
announcements: [changelog]
created: 2026-03-08
status: active
epic: ""
---

# Local Dev Environment

## Problem

The Flink SQL Pipeline Platform has no local development setup. Developers cannot run the application locally without:
- A running Kubernetes cluster (for `TenantNamespaceProvisioner`)
- A live Keycloak instance (JWT issuer-uri required at startup)
- Manual configuration of PostgreSQL, Kafka, Schema Registry

This makes onboarding slow, iterative development difficult, and local integration testing impossible without significant manual effort.

## Solution

Add a complete local development environment comprising:
1. A **Spring `local` profile** that stubs K8s and provides a mock JWT decoder so the backend starts without a real cluster or Keycloak
2. A **Docker Compose stack** with all platform dependencies (postgres, keycloak, kafka, zookeeper, schema-registry, backend, frontend), Keycloak realm import, and a seed data SQL script that pre-creates a demo tenant and pipeline
3. A **kind cluster setup script** that installs the Flink Kubernetes Operator and MinIO (S3) and exposes the Flink Web UI at localhost:8081 for full-fidelity pipeline deployment testing
4. A **LOCAL_DEV.md** guide that walks a developer from clone to running platform in under 10 minutes

## Domain Model

### Entities
- **Tenant** — org-level entity with slug, fid, OAuth2 credentials, K8s namespace, quota; created via POST /api/v1/tenants
- **Pipeline** — SQL job with Kafka sources/sinks, parallelism, status lifecycle (DRAFT→DEPLOYING→RUNNING→SUSPENDED→DELETED)
- **PipelineDeployment** — tracks K8s resource names (FlinkDeployment CRD name, ConfigMap name), savepoint path, lifecycle state

### Relationships
- Tenant has many Pipelines (quota-bounded)
- Pipeline has one PipelineDeployment (created on deploy)
- PipelineDeployment references K8s resources in the Tenant's namespace

### Data Sources
- **PostgreSQL** (JDBC): stores Tenant, Pipeline, PipelineDeployment via Spring Data JPA + Flyway migrations
- **Keycloak** (OAuth2 OIDC): JWT issuer; admin API for registering tenant OAuth2 clients (`oauth2.admin.url`)
- **Kafka** (referenced in Flink SQL DDL only — no Spring Kafka consumer): bootstrap servers in pipeline source/sink config
- **Schema Registry** (REST): validates Avro subjects on pipeline create (`SchemaRegistryValidationService`)
- **Kubernetes API** (Fabric8): namespace provisioning + FlinkDeployment CRD CRUD (`TenantNamespaceProvisioner`, `FlinkOrchestrationServiceImpl`)
- **S3/MinIO** (path reference in FlinkDeployment spec): Flink checkpoint/savepoint storage

### Data Gaps
- No existing `local` Spring profile — must be created
- No `NoOpTenantNamespaceProvisioner` conditional bean — must be added
- SSRF blocklist in `SchemaRegistryValidationService` blocks `172.16-31.*` Docker bridge IPs — must be bypassed in local profile
- JWT issuer-uri required at startup — must mock `JwtDecoder` or run real Keycloak; we do both (real Keycloak in Docker Compose, mock decoder for stub mode)

## Success Criteria
- [ ] `docker compose up` starts all services and all pass healthchecks within 3 minutes
- [ ] A developer can register a tenant via the UI at localhost and receive the fid+fidSecret
- [ ] A developer can create a pipeline via the 5-step UI editor and see it persist in the database
- [ ] Seed script auto-creates a demo tenant + sample pipeline on first `docker compose up`
- [ ] `./dev/setup-kind.sh` creates a kind cluster with Flink Kubernetes Operator installed, verifiable via `kubectl get crd flinkdeployments.flink.apache.org`
- [ ] Flink Web UI is accessible at localhost:8081 after kind cluster setup
- [ ] `mvn clean verify` passes locally (unit + Testcontainers integration tests) without pre-running services
- [ ] SSRF blocklist patched to allow Docker-internal IP ranges in local Spring profile
- [ ] `LOCAL_DEV.md` documents complete setup walkable in ≤10 minutes

## Context

- **Existing stubs**: `NoOpOAuth2ProviderClient` already activates when `oauth2.admin.url` is blank — Keycloak admin calls are already stubbed
- **No existing `local` profile**: `application.properties` has prod config; only `application-test.properties` (for Testcontainers) exists
- **Backend Dockerfile**: does not exist yet — unit-02 must create it
- **Frontend Dockerfile**: exists at `frontend/Dockerfile` (multi-stage node:20-alpine + nginx:1.25-alpine)
- **SSRF ranges blocked**: `127.*`, `10.*`, `172.16-31.*`, `192.168.*`, `169.254.*`, `metadata.internal` — Docker bridge typically uses `172.17-20.*` which is blocked
- **Schema Registry in Docker Compose**: will use `confluentinc/cp-schema-registry` on the Docker bridge; its IP will be in the blocked range — local profile must whitelist Docker service hostnames or disable the SSRF check for `localhost:*` and Docker service names
