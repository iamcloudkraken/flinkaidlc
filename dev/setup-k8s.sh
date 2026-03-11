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

# 1. Install cert-manager (required by Flink Operator webhook)
echo "==> Installing cert-manager v1.14.4..."
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
echo "    Waiting for cert-manager to be ready..."
kubectl rollout status deployment/cert-manager -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-cainjector -n cert-manager --timeout=120s
kubectl rollout status deployment/cert-manager-webhook -n cert-manager --timeout=120s

# 2. Install Flink Kubernetes Operator via Helm
echo "==> Installing Flink Kubernetes Operator v${FLINK_OPERATOR_VERSION}..."
helm repo add flink-operator-repo "https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/" 2>/dev/null || \
  helm repo add flink-operator-repo "https://downloads.apache.org/flink/flink-kubernetes-operator-${FLINK_OPERATOR_VERSION}/"
helm repo update
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
