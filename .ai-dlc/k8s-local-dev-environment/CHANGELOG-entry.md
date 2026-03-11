## [Unreleased]

### Added

- **Kubernetes local dev environment** using Docker Desktop's built-in single-node cluster
  - `make k8s-setup` — one-time bootstrap: installs cert-manager v1.14.4 and Flink Kubernetes Operator v1.8.0 via Helm
  - `make k8s-up` — builds backend + frontend Docker images, applies all K8s manifests, waits for pod readiness, prints access URLs
  - `make k8s-down` — deletes `ns-enterprise` and `ns-controlplane`; preserves tenant namespaces for debugging
  - `make k8s-status` — shows pod readiness across all platform namespaces
  - `dev/setup-k8s.sh` — idempotent cluster bootstrap script with docker-desktop context guard
- **ns-enterprise namespace** (`dev/k8s/enterprise/`) with Kubernetes manifests for: Zookeeper, Kafka, Schema Registry, Keycloak, MinIO, Kafka UI, Iceberg REST
- **ns-controlplane namespace** (`dev/k8s/controlplane/`) with manifests for: PostgreSQL, backend (Spring Boot), frontend (Nginx), Nginx Flink proxy
- **`local-k8s` Spring profile** — additive to `local`; sets `k8s.provisioner.enabled=true`, in-cluster service DNS for DB/Kafka, and `kubernetes.platform-namespace=ns-controlplane`
- **Nginx Flink proxy** (`nginx-flink-proxy`) routing `/flink/{tenant_slug}/{pipeline_id}/` → Flink REST service via in-cluster DNS — no `kubectl port-forward` required

### Changed

- `make flink-ui` — now prints the Nginx proxy URL (`http://localhost:30080/flink/$(TENANT)/$(PIPELINE)/`) instead of running `kubectl port-forward`
- `TenantNamespaceProvisioner` NetworkPolicy updated: ingress source namespace label changed from `flink-platform` → `ns-controlplane`

### Notes

- The existing `make up` (Docker Compose) stack is **unchanged** — both modes coexist
- Docker Desktop Kubernetes shares the Docker daemon; images built locally are immediately available to pods with `imagePullPolicy: Never` — no registry push needed
- The existing `dev/setup-kind.sh` (kind cluster) is preserved and untouched
