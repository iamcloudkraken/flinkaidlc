---
workflow: default
git:
  change_strategy: intent
  auto_merge: true
  auto_squash: false
announcements: [changelog, release-notes, social-posts, blog-draft]
created: 2026-03-10
status: active
epic: ""
---

# Kubernetes Local Dev Environment

## Problem

The current local dev setup uses Docker Compose, which runs all services flat with no namespace isolation and no Kubernetes. Developers cannot test Flink job deployment, TenantNamespaceProvisioner behavior, or the Nginx-based Flink UI proxy without a real cluster. The `make flink-ui` target requires manual `kubectl port-forward` per pipeline — not scalable and not representative of production topology.

## Solution

Replace the Docker Compose stack with a Kubernetes-based local dev environment using Docker Desktop's built-in single-node cluster. All services run in two isolated namespaces:

- **ns_enterprise**: Kafka, Zookeeper, Schema Registry, Keycloak, MinIO, Kafka UI
- **ns_controlplane**: Postgres, backend (Spring Boot), frontend (Nginx), Nginx Flink proxy

Tenant Flink jobs deploy into dynamically-provisioned `tenant-{slug}` namespaces (created by TenantNamespaceProvisioner on tenant onboarding). A standalone Nginx deployment in ns_controlplane routes `/flink/{tenant_slug}/{pipeline_id}/` → Flink REST service via in-cluster DNS — no port-forwarding needed.

The existing Docker Compose setup (`make up`, `local` Spring profile) is preserved untouched. The new K8s mode is activated via `make k8s-up` and a new `local-k8s` Spring profile.

## Domain Model

### Namespaces

- **ns_enterprise**: Infrastructure services shared across tenants. Label: `app.kubernetes.io/part-of: flink-platform-enterprise`
- **ns_controlplane**: Platform services (backend, frontend). Label: `app.kubernetes.io/part-of: flink-platform-controlplane` — this label is used in TenantNamespaceProvisioner NetworkPolicy to allow ingress from the platform.
- **tenant-{slug}**: Per-tenant Flink job namespaces, created dynamically by TenantNamespaceProvisioner on tenant registration.

### Services and DNS

| Service | Namespace | Internal DNS |
|---------|-----------|-------------|
| zookeeper | ns_enterprise | zookeeper.ns_enterprise.svc.cluster.local:2181 |
| kafka | ns_enterprise | kafka.ns_enterprise.svc.cluster.local:29092 |
| schema-registry | ns_enterprise | schema-registry.ns_enterprise.svc.cluster.local:8081 |
| keycloak | ns_enterprise | keycloak.ns_enterprise.svc.cluster.local:8080 |
| minio | ns_enterprise | minio.ns_enterprise.svc.cluster.local:9000 |
| kafka-ui | ns_enterprise | kafka-ui.ns_enterprise.svc.cluster.local:8080 |
| postgresql | ns_controlplane | postgresql.ns_controlplane.svc.cluster.local:5432 |
| backend | ns_controlplane | backend.ns_controlplane.svc.cluster.local:8090 |
| frontend (Nginx) | ns_controlplane | frontend.ns_controlplane.svc.cluster.local:80 |
| nginx-flink-proxy | ns_controlplane | nginx-flink-proxy.ns_controlplane.svc.cluster.local:80 |

### Nginx Routing (frontend Nginx in ns_controlplane)

- `/api/*` → `backend.ns_controlplane.svc.cluster.local:8090`
- `/realms/*` → `keycloak.ns_enterprise.svc.cluster.local:8080`
- `/kafka-ui/*` → `kafka-ui.ns_enterprise.svc.cluster.local:8080`
- `/flink/*` → `nginx-flink-proxy.ns_controlplane.svc.cluster.local:80`

### Nginx Flink Proxy Routing

- Request: `/flink/{tenant_slug}/{pipeline_id}/...`
- Rewrite: strip prefix, proxy to `pipeline-{pipeline_id}-rest.tenant-{tenant_slug}.svc.cluster.local:8081`
- Pattern: `rewrite ^/flink/[^/]+/([^/]+)/(.*)$ /$2 break;`

### Key Backend Entities

- **TenantNamespaceProvisioner**: `@ConditionalOnProperty(k8s.provisioner.enabled=true)`. Creates Namespace, ServiceAccount "flink", Role, RoleBinding, ResourceQuota, NetworkPolicy per tenant. NetworkPolicy must allow ingress from ns_controlplane (not "flink-platform").
- **FlinkDeploymentBuilder**: Sets `imagePullPolicy: Never`, resource name = `pipeline-{pipeline_id}`. Flink Operator creates REST service `pipeline-{pipeline_id}-rest`.
- **LocalDataSeeder**: Seeds Kafka topics. In K8s profile, bootstrap = `kafka.ns_enterprise.svc.cluster.local:29092`.

### Existing K8s Setup (kind)

The existing `dev/setup-kind.sh` targets a `kind` cluster named `flink-local`. The new setup targets Docker Desktop's built-in cluster. The new `dev/setup-k8s.sh` installs Flink Operator v1.8.0 and MinIO via Helm, then applies namespace manifests.

### Images

All images built locally with `docker build`. `imagePullPolicy: Never` — Docker Desktop shares the Docker daemon so no registry is needed.

## Success Criteria

- [ ] `make k8s-up` completes successfully — all pods in `ns_enterprise` and `ns_controlplane` reach `Running` state
- [ ] Frontend accessible at `http://localhost`, login via Keycloak works end-to-end
- [ ] Backend responds `200` at `/api/v1/health` using `local-k8s` Spring profile
- [ ] Kafka UI accessible at `http://localhost/kafka-ui/` showing topics and consumer groups from the local Kafka cluster
- [ ] Creating a tenant via `POST /api/v1/tenants` provisions a `tenant-{slug}` namespace with ServiceAccount, Role, RoleBinding, ResourceQuota, and NetworkPolicy
- [ ] Deploying a Flink pipeline creates a FlinkDeployment CRD in `tenant-{slug}`, Flink UI accessible at `http://localhost/flink/{tenant_slug}/{pipeline_id}/`
- [ ] `make k8s-down` deletes `ns_enterprise` and `ns_controlplane` without errors
- [ ] `make k8s-status` shows pod readiness summary for all AI-DLC namespaces
- [ ] `make k8s-up` is idempotent — running it twice produces no errors
- [ ] `make up` (Docker Compose) continues to work independently

## Context

- Docker Desktop Kubernetes is a single-node cluster — no multi-cluster complexity
- Flink Operator v1.8.0 installed via Helm in `flink-operator` namespace (cert-manager v1.14.4 required first)
- MinIO: Bitnami Helm chart, credentials minioadmin/minioadmin, bucket flink-local, in ns_enterprise
- Keycloak: quay.io/keycloak/keycloak:24.0, admin/admin, realm/client config carried over from Docker Compose
- Spring profile `local-k8s` is additive to `local` — same mock JWT decoder (blank issuer-uri), different DB/Kafka URLs and k8s.provisioner.enabled=true
- `kubernetes.namespace-prefix` stays "tenant-" (unchanged from Docker Compose mode)
- `kubernetes.platform-namespace` changes from "flink-platform" → "ns_controlplane"
- NetworkPolicy in TenantNamespaceProvisioner must be updated: from namespace label `flink-platform` → `ns_controlplane`
- Docker Compose `make up` remains untouched — developers can switch between modes
