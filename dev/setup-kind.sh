#!/usr/bin/env bash
set -euo pipefail

CLUSTER_NAME="flink-local"
FLINK_OPERATOR_VERSION="1.8.0"
MINIO_CHART_VERSION="14.6.0"

echo "==> Checking prerequisites..."
command -v kind >/dev/null || { echo "ERROR: 'kind' not found. Install kind: https://kind.sigs.k8s.io/docs/user/quick-start/"; exit 1; }
command -v helm >/dev/null || { echo "ERROR: 'helm' not found. Install helm: https://helm.sh/docs/intro/install/"; exit 1; }
command -v kubectl >/dev/null || { echo "ERROR: 'kubectl' not found. Install kubectl: https://kubernetes.io/docs/tasks/tools/"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Creating kind cluster '$CLUSTER_NAME'..."
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "    Cluster '$CLUSTER_NAME' already exists, skipping creation."
else
  kind create cluster --name "$CLUSTER_NAME" --config "${SCRIPT_DIR}/kind-config.yaml"
fi

kubectl config use-context "kind-${CLUSTER_NAME}"

echo "==> Installing cert-manager v1.14.4 (required by Flink Operator webhook)..."
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
echo "    Waiting for cert-manager deployments to be ready..."
kubectl rollout status deployment/cert-manager -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-cainjector -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-webhook -n cert-manager --timeout=120s

echo "==> Installing Flink Kubernetes Operator v${FLINK_OPERATOR_VERSION}..."
FLINK_DOWNLOADS_URL="https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/"
FLINK_ARCHIVE_URL="https://archive.apache.org/dist/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/"
if ! helm repo add flink-operator-repo "$FLINK_DOWNLOADS_URL" 2>/dev/null; then
  helm repo remove flink-operator-repo 2>/dev/null || true
  helm repo add flink-operator-repo "$FLINK_ARCHIVE_URL"
fi
helm repo update
helm upgrade --install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
  --namespace flink-operator \
  --create-namespace \
  --version "${FLINK_OPERATOR_VERSION}" \
  --set webhook.create=true \
  --wait --timeout 3m

echo "==> Verifying Flink CRD..."
kubectl get crd flinkdeployments.flink.apache.org

echo "==> Installing MinIO v${MINIO_CHART_VERSION} (local S3-compatible store)..."
helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || \
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

echo ""
echo "==> Local Flink K8s environment is ready!"
echo ""
echo "    Cluster:        kind-${CLUSTER_NAME}"
echo "    Flink Operator: running in namespace 'flink-operator'"
echo "    MinIO S3 API:   http://localhost:9000  (bucket: flink-local)"
echo "    MinIO Console:  http://localhost:9090"
echo "    Credentials:    minioadmin / minioadmin"
echo ""
echo "==> Add these to src/main/resources/application-local.properties (or your .env):"
echo "    k8s.provisioner.enabled=true"
echo "    flink.s3.endpoint=http://localhost:9000"
echo "    flink.s3.access-key=minioadmin"
echo "    flink.s3.secret-key=minioadmin"
echo "    flink.s3.checkpoint-base=s3://flink-local/checkpoints"
echo "    flink.s3.savepoint-base=s3://flink-local/savepoints"
echo ""
echo "==> To port-forward the Flink Web UI for a running pipeline:"
echo "    kubectl port-forward svc/<pipeline-name>-rest 8081:8081 -n tenant-<slug>"
echo "    Then open http://localhost:8081"
echo ""
echo "==> NOTE: Run the backend natively (not in Docker) when using kind:"
echo "    mvn spring-boot:run -Dspring.profiles.active=local"
echo "    (The backend inside Docker cannot reach the kind cluster API via localhost.)"
