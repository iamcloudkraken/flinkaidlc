---
status: pending
depends_on: []
branch: ai-dlc/k8s-local-dev-environment/01-enterprise-namespace
discipline: devops
workflow: ""
ticket: ""
---

# unit-01-enterprise-namespace

## Description

Create Kubernetes manifests for the `ns_enterprise` namespace containing all shared infrastructure services: Zookeeper, Kafka, Schema Registry, Keycloak, MinIO, and Kafka UI. These services are the equivalent of the Docker Compose `infra` target, now running as K8s Deployments in a dedicated namespace.

## Discipline

devops — infrastructure manifests, Kubernetes YAML, Helm values.

## Domain Entities

- **ns_enterprise namespace**: Label `app.kubernetes.io/part-of: flink-platform-enterprise`
- **Zookeeper**: confluentinc/cp-zookeeper:7.6.0, port 2181
- **Kafka**: confluentinc/cp-kafka:7.6.0, ports 9092 (external) + 29092 (internal broker)
- **Schema Registry**: confluentinc/cp-schema-registry:7.6.0, port 8081
- **Keycloak**: quay.io/keycloak/keycloak:24.0, port 8080, admin/admin credentials, dev mode
- **MinIO**: Bitnami Helm chart OR bitnami/minio image, minioadmin/minioadmin, bucket flink-local, ports 9000 (API) + 9090 (console)
- **Kafka UI**: provectuslabs/kafka-ui:latest, port 8080, configured to connect to Kafka at kafka.ns_enterprise.svc.cluster.local:29092 and Schema Registry at schema-registry.ns_enterprise.svc.cluster.local:8081

## Data Sources

All images pulled from Docker Hub / Quay. No registry needed — Docker Desktop daemon is shared.

Existing Docker Compose configuration (reference for env vars):
- `docker-compose.yml` in repo root — contains all image versions, env vars, and port mappings
- Key env vars:
  - Zookeeper: `ZOOKEEPER_CLIENT_PORT=2181`, `ZOOKEEPER_TICK_TIME=2000`
  - Kafka: `KAFKA_BROKER_ID=1`, `KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181`, `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT`, `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`
  - Schema Registry: `SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=kafka:29092`, `SCHEMA_REGISTRY_HOST_NAME=schema-registry`
  - Keycloak: `KEYCLOAK_ADMIN=admin`, `KEYCLOAK_ADMIN_PASSWORD=admin`, start in dev mode (`start-dev`)
  - MinIO: `MINIO_ROOT_USER=minioadmin`, `MINIO_ROOT_PASSWORD=minioadmin`, create bucket `flink-local`
  - Kafka UI: `KAFKA_CLUSTERS_0_NAME=local`, `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:29092`, `KAFKA_CLUSTERS_0_SCHEMAREGISTRY=http://schema-registry:8081`

## Technical Specification

Create directory `dev/k8s/enterprise/` containing:

```
dev/k8s/enterprise/
├── namespace.yaml           # Namespace: ns_enterprise
├── zookeeper.yaml           # Deployment + Service
├── kafka.yaml               # Deployment + Service
├── schema-registry.yaml     # Deployment + Service
├── keycloak.yaml            # Deployment + Service
├── minio.yaml               # Deployment + Service + PersistentVolumeClaim (or use emptyDir for dev)
└── kafka-ui.yaml            # Deployment + Service
```

### namespace.yaml
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ns_enterprise
  labels:
    app.kubernetes.io/part-of: flink-platform-enterprise
```

### Service naming (critical for unit-02 and unit-03)
Every Service must use the exact names used in the domain model DNS table:
- `zookeeper` (not `cp-zookeeper`)
- `kafka`
- `schema-registry`
- `keycloak`
- `minio`
- `kafka-ui`

This is because unit-02 Nginx config and unit-03 backend properties reference these exact DNS names.

### Kafka
Kafka requires the `KAFKA_ADVERTISED_LISTENERS` env var to advertise both the internal broker address (for inter-service communication within the cluster) and an optional external address. In K8s:
- Internal: `PLAINTEXT://kafka.ns_enterprise.svc.cluster.local:29092`
- The `KAFKA_LISTENERS` must bind to `0.0.0.0`

### Keycloak
Run in dev mode (`start-dev` command). No TLS configuration needed for local dev.
Keycloak needs a realm + client for the platform. Check if there's an existing realm export in the repo (look for `keycloak/` or `dev/keycloak/`). If found, mount it as a ConfigMap and import on startup. If not, document that the realm must be manually configured after first start (acceptable for initial K8s dev setup).

### MinIO
Use `bitnami/minio:latest` image directly (not Helm, to keep it declarative in YAML). Create a Job or initContainer to create the `flink-local` bucket on startup, OR use MinIO's `MINIO_DEFAULT_BUCKETS` env var if available. Use emptyDir for storage (dev environment, data not persisted across pod restarts).

### Resource limits
Set modest resource requests/limits for single-node Docker Desktop:
- Zookeeper: 256Mi memory, 0.25 CPU
- Kafka: 512Mi memory, 0.5 CPU
- Schema Registry: 256Mi memory, 0.25 CPU
- Keycloak: 512Mi memory, 0.5 CPU
- MinIO: 256Mi memory, 0.25 CPU
- Kafka UI: 256Mi memory, 0.25 CPU

### imagePullPolicy
All Deployments: `imagePullPolicy: IfNotPresent` (these are public images from registries, not locally built).

## Success Criteria

- [ ] `kubectl apply -f dev/k8s/enterprise/` succeeds without errors
- [ ] All 6 Deployments reach `Running` state in ns_enterprise: `kubectl get pods -n ns_enterprise`
- [ ] Kafka is reachable within the cluster: a test consumer can connect to `kafka.ns_enterprise.svc.cluster.local:29092`
- [ ] Schema Registry health endpoint returns 200: `kubectl exec -n ns_enterprise <schema-registry-pod> -- curl -s http://localhost:8081/`
- [ ] Kafka UI shows the local cluster and its topics at its pod's port 8080
- [ ] MinIO console accessible at pod's port 9090, bucket `flink-local` exists

## Risks

- **Kafka advertised listeners in K8s**: Kafka's `KAFKA_ADVERTISED_LISTENERS` must use the K8s Service DNS name, not `localhost` or the Docker Compose hostname. Getting this wrong causes producers/consumers outside the pod to fail with "not a leader" errors. Mitigation: use `PLAINTEXT://kafka.ns_enterprise.svc.cluster.local:29092` for the internal listener.
- **Keycloak realm config**: The existing Docker Compose setup may have a pre-configured realm that's not exported to files. If so, the builder must document the manual realm setup steps. Mitigation: check for realm export files in the repo first.
- **MinIO bucket creation**: MinIO doesn't auto-create the `flink-local` bucket. A Job or init script is needed. Mitigation: use `MINIO_DEFAULT_BUCKETS=flink-local` env var if the bitnami image supports it, otherwise create an init Job.
- **Resource pressure on Docker Desktop**: Single-node K8s with 6 services may exceed default Docker Desktop memory allocation (2GB). Mitigation: set resource limits conservatively, document how to increase Docker Desktop memory in setup notes.

## Boundaries

This unit does NOT handle:
- ns_controlplane services (unit-02)
- Backend Spring profile configuration (unit-03)
- Makefile targets or setup scripts (unit-04)
- Flink Operator installation (unit-04)
- TLS or production-grade configuration

## Notes

- Namespace name in Kubernetes uses underscore: `ns_enterprise`. Note: Kubernetes namespace names support lowercase letters, digits, and hyphens only — underscores are NOT allowed per RFC 1123. Use `ns-enterprise` instead. Update all references accordingly. (Same applies to `ns_controlplane` → `ns-controlplane`.)
- Actually re-check: the discovery doc uses `ns_enterprise` throughout. The builder should verify if Kubernetes allows underscores. Per RFC 1123, they are NOT allowed. Use `ns-enterprise` and `ns-controlplane` for actual K8s namespace names, and update the application-local-k8s.properties accordingly. Document this discrepancy.
- The Docker Compose `infra` Makefile target currently starts Kafka, Zookeeper, Schema Registry, and Keycloak. Use those env var values directly.
