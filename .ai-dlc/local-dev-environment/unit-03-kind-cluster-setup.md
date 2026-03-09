---
status: pending
depends_on: []
branch: ai-dlc/local-dev-environment/03-kind-cluster-setup
discipline: devops
workflow: ""
ticket: ""
---

# unit-03-kind-cluster-setup

## Description

Create a shell script `dev/setup-kind.sh` that provisions a local kind (Kubernetes in Docker) cluster, installs the Flink Kubernetes Operator via Helm, deploys MinIO as the local S3-compatible checkpoint/savepoint store, and exposes the Flink Web UI at `localhost:8081`. This enables full-fidelity pipeline deployment testing without a real cloud Kubernetes cluster.

## Discipline

devops - This unit will be executed by devops-focused agents.

## Domain Entities

- **FlinkDeployment CRD** — `flink.apache.org/v1beta1`; installed by the Flink Kubernetes Operator Helm chart; required for `FlinkOrchestrationServiceImpl.deploy()` to work
- **Tenant namespace** — the real `KubernetesTenantNamespaceProvisioner` creates `tenant-{slug}` namespaces; kind cluster must have the Operator watching all namespaces (or the tenant namespace specifically)
- **S3 checkpoint/savepoint storage** — `FlinkDeploymentBuilder` hardcodes `s3://...` paths; MinIO provides a local S3 endpoint

## Data Sources

- `src/main/java/com/flinkaidlc/platform/orchestration/FlinkDeploymentBuilder.java` — inspect `flink.s3.checkpoint-base` and `flink.s3.savepoint-base` config keys and the S3 endpoint/access key config (if any)
- `src/main/resources/application-local.properties` (from unit-01) — will need `flink.s3.*` values updated to point to MinIO endpoint

## Technical Specification

### File structure to create

```
dev/
  setup-kind.sh         # Main setup script
  teardown-kind.sh      # Cleanup script
  kind-config.yaml      # kind cluster config (extra port mappings)
  minio-values.yaml     # Helm values for MinIO
```

### `dev/kind-config.yaml`

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30081   # Flink Web UI NodePort
        hostPort: 8081
        protocol: TCP
      - containerPort: 30090   # MinIO Console NodePort
        hostPort: 9090
        protocol: TCP
```

### `dev/setup-kind.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="flink-local"
FLINK_OPERATOR_VERSION="1.8.0"
MINIO_CHART_VERSION="14.6.0"

echo "==> Checking prerequisites..."
command -v kind >/dev/null || { echo "Install kind: https://kind.sigs.k8s.io/docs/user/quick-start/"; exit 1; }
command -v helm >/dev/null || { echo "Install helm: https://helm.sh/docs/intro/install/"; exit 1; }
command -v kubectl >/dev/null || { echo "Install kubectl"; exit 1; }

echo "==> Creating kind cluster '$CLUSTER_NAME'..."
kind get clusters | grep -q "$CLUSTER_NAME" && echo "Cluster already exists, skipping." || \
  kind create cluster --name "$CLUSTER_NAME" --config "$(dirname "$0")/kind-config.yaml"

kubectl config use-context "kind-${CLUSTER_NAME}"

echo "==> Installing cert-manager (required by Flink Operator)..."
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
kubectl rollout status deployment/cert-manager -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-webhook -n cert-manager --timeout=120s

echo "==> Installing Flink Kubernetes Operator v${FLINK_OPERATOR_VERSION}..."
helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/
helm repo update
helm upgrade --install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
  --namespace flink-operator \
  --create-namespace \
  --version "${FLINK_OPERATOR_VERSION}" \
  --set webhook.create=true \
  --wait --timeout 3m

echo "==> Verifying Flink CRD..."
kubectl get crd flinkdeployments.flink.apache.org

echo "==> Installing MinIO (local S3)..."
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
helm upgrade --install minio bitnami/minio \
  --namespace minio \
  --create-namespace \
  --version "${MINIO_CHART_VERSION}" \
  --set auth.rootUser=minioadmin \
  --set auth.rootPassword=minioadmin \
  --set defaultBuckets="flink-local" \
  --set service.type=NodePort \
  --set service.nodePorts.api=30900 \
  --set consoleService.type=NodePort \
  --set consoleService.nodePorts.console=30090 \
  --wait --timeout 3m

echo "==> MinIO S3 endpoint: http://localhost:9000"
echo "    Access key: minioadmin / minioadmin"
echo "    Bucket: flink-local"

echo ""
echo "==> Local Flink K8s environment ready!"
echo ""
echo "    Flink Operator: running in namespace 'flink-operator'"
echo "    MinIO S3:       http://localhost:9000  (bucket: flink-local)"
echo "    MinIO Console:  http://localhost:9090"
echo ""
echo "==> Add these to application-local.properties or your .env:"
echo "    k8s.provisioner.enabled=true"
echo "    flink.s3.endpoint=http://localhost:9000"
echo "    flink.s3.access-key=minioadmin"
echo "    flink.s3.secret-key=minioadmin"
echo "    flink.s3.checkpoint-base=s3://flink-local/checkpoints"
echo "    flink.s3.savepoint-base=s3://flink-local/savepoints"
echo ""
echo "==> To access Flink Web UI: port-forward a running FlinkDeployment:"
echo "    kubectl port-forward svc/<pipeline-name>-rest 8081:8081 -n tenant-demo"
echo "    Then open http://localhost:8081"
```

### `dev/teardown-kind.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
CLUSTER_NAME="flink-local"
echo "==> Deleting kind cluster '$CLUSTER_NAME'..."
kind delete cluster --name "$CLUSTER_NAME"
echo "Done."
```

### Flink Web UI access

The Flink Web UI is served by the JobManager pod's REST service. It is NOT a cluster-level service — each FlinkDeployment has its own REST service named `{deployment-name}-rest` in the tenant namespace.

Therefore, the setup script cannot pre-configure a static port for the Flink Web UI. Instead:
1. The setup script documents the `kubectl port-forward` command in its output
2. Add a convenience `Makefile` target (in the project root Makefile from unit-02):
   ```makefile
   flink-ui:  ## Port-forward the Flink Web UI for the most recent FlinkDeployment
       @PIPELINE=$$(kubectl get flinkdeployment --all-namespaces -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
       NS=$$(kubectl get flinkdeployment --all-namespaces -o jsonpath='{.items[0].metadata.namespace}' 2>/dev/null); \
       if [ -z "$$PIPELINE" ]; then echo "No FlinkDeployment found. Deploy a pipeline first."; exit 1; fi; \
       echo "Port-forwarding Flink UI for $$PIPELINE in $$NS..."; \
       kubectl port-forward svc/$$PIPELINE-rest 8081:8081 -n $$NS
   ```

This gives `make flink-ui` as a developer convenience command.

### `application-local.properties` additions (for full K8s mode)

After running `setup-kind.sh`, a developer can switch the backend to use real K8s by setting in their environment (or a `.env.local` file):

```properties
k8s.provisioner.enabled=true
flink.s3.endpoint=http://localhost:9000
flink.s3.access-key=minioadmin
flink.s3.secret-key=minioadmin
```

The `KUBECONFIG` must point to the kind cluster context (`kind-flink-local`).

## Success Criteria

- [ ] `dev/setup-kind.sh` runs successfully on macOS (Apple Silicon and Intel) with kind, helm, kubectl installed
- [ ] After script completion: `kubectl get crd flinkdeployments.flink.apache.org` succeeds (Flink CRD exists)
- [ ] After script completion: `kubectl get pods -n flink-operator` shows the operator pod running
- [ ] After script completion: `curl http://localhost:9000/minio/health/live` returns 200 (MinIO up)
- [ ] Bucket `flink-local` exists in MinIO (verifiable via MinIO console at `http://localhost:9090`)
- [ ] `make flink-ui` port-forwards Flink UI when at least one FlinkDeployment exists
- [ ] `dev/teardown-kind.sh` cleanly removes the kind cluster

## Risks

- **cert-manager timing**: cert-manager webhook may not be ready when Flink Operator installs. Mitigation: `--wait --timeout 3m` on cert-manager rollout before Helm install.
- **Helm chart version drift**: Flink Operator Helm chart URL changes with version. Mitigation: pin exact version; document upgrade path in LOCAL_DEV.md.
- **Apple Silicon (ARM64) compatibility**: Some images may not have ARM64 variants. Mitigation: Flink Operator 1.8.0+ and MinIO support multi-arch. Test on Apple Silicon.
- **kind cluster port conflicts**: Port 8081 may be in use. Mitigation: check port availability at script start; document alternative port in LOCAL_DEV.md.
- **Backend KUBECONFIG in Docker Compose**: When running backend in Docker, it cannot reach the kind cluster API via `localhost`. Full K8s mode requires running the backend outside Docker (e.g., `mvn spring-boot:run -Dspring.profiles.active=local`). Mitigation: document this limitation clearly.

## Boundaries

This unit does NOT:
- Modify any Java source files (unit-01 handles Spring profile changes)
- Create the Docker Compose file (unit-02)
- Write LOCAL_DEV.md (unit-04)
- Deploy any FlinkDeployments — that happens via the platform API after a pipeline is created

## Notes

- Flink Operator version `1.8.0` supports Flink 1.18–1.20. The platform uses Flink 1.20 images.
- MinIO bitnami chart version `14.x` provides S3-compatible API at port 9000 and web console at 9090.
- The kind cluster uses the name `flink-local` to avoid conflicts with other kind clusters the developer may have.
- The `setup-kind.sh` script is idempotent: re-running it skips cluster creation if the cluster already exists.
- For the backend to use the kind cluster when running inside Docker Compose, the Docker network would need to be bridged to the kind network — this is complex and out of scope. Full K8s mode is only supported with the backend running natively (outside Docker).
