#!/usr/bin/env bash
set -euo pipefail

FLINK_OPERATOR_VERSION="1.8.0"

echo "=== Setting up Flink Platform on Docker Desktop Kubernetes ==="
echo ""

echo "==> Checking prerequisites..."
command -v kubectl >/dev/null || { echo "ERROR: 'kubectl' not found. Install kubectl: https://kubernetes.io/docs/tasks/tools/"; exit 1; }
command -v helm >/dev/null || { echo "ERROR: 'helm' not found. Install helm: https://helm.sh/docs/intro/install/"; exit 1; }

# Verify kubectl context is docker-desktop
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "")
if [ "$CURRENT_CONTEXT" != "docker-desktop" ]; then
  echo "ERROR: kubectl context is '${CURRENT_CONTEXT}', expected 'docker-desktop'"
  echo "Switch context: kubectl config use-context docker-desktop"
  exit 1
fi

# Verify the cluster API server is reachable
echo "==> Verifying cluster connectivity..."
if ! kubectl cluster-info --request-timeout=10s >/dev/null 2>&1; then
  echo ""
  echo "ERROR: Cannot connect to the Kubernetes API server."
  echo ""
  echo "Ensure Docker Desktop Kubernetes is running:"
  echo "  Docker Desktop → Settings → Kubernetes → 'Kubernetes is running' (green)"
  echo ""
  echo "If it just started, wait 30–60 seconds and retry."
  exit 1
fi
echo "    Cluster reachable."

# 1. Install cert-manager (required by Flink Operator webhook)
echo "==> Installing cert-manager v1.14.4..."
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
echo "    Waiting for cert-manager to be ready..."
kubectl rollout status deployment/cert-manager -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-cainjector -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-webhook -n cert-manager --timeout=120s

# 2. Install Flink Kubernetes Operator via Helm
# The Apache downloads mirror only keeps the latest release; older versions are at archive.apache.org
echo "==> Installing Flink Kubernetes Operator v${FLINK_OPERATOR_VERSION}..."
FLINK_DOWNLOADS_URL="https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/"
FLINK_ARCHIVE_URL="https://archive.apache.org/dist/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/"

if helm repo add flink-operator-repo "$FLINK_DOWNLOADS_URL" 2>/dev/null; then
  echo "    Using Apache downloads mirror."
elif helm repo add flink-operator-repo "$FLINK_ARCHIVE_URL" 2>/dev/null; then
  echo "    Using Apache archive mirror."
else
  # Remove stale entry and re-add from archive (repo already exists with wrong URL)
  helm repo remove flink-operator-repo 2>/dev/null || true
  helm repo add flink-operator-repo "$FLINK_ARCHIVE_URL"
  echo "    Using Apache archive mirror (re-added)."
fi
helm repo update flink-operator-repo
helm upgrade --install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
  --namespace flink-operator \
  --create-namespace \
  --version "${FLINK_OPERATOR_VERSION}" \
  --set webhook.create=true \
  --wait --timeout 3m

echo "==> Verifying Flink CRD..."
kubectl get crd flinkdeployments.flink.apache.org

echo ""
echo "=== Setup complete! ==="
echo "Run 'make k8s-up' to start all platform services."
