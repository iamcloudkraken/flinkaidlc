---
status: completed
depends_on: [unit-01-enterprise-namespace, unit-02-controlplane-namespace, unit-03-backend-k8s-config]
branch: ai-dlc/k8s-local-dev-environment/04-setup-makefile
discipline: devops
workflow: ""
ticket: ""
---

# unit-04-setup-makefile

## Description

Create `dev/setup-k8s.sh` — a one-time cluster bootstrapping script that installs cert-manager and the Flink Kubernetes Operator via Helm into Docker Desktop's built-in cluster. Then add three Makefile targets (`k8s-up`, `k8s-down`, `k8s-status`) that build Docker images and apply/delete the K8s manifests from units 01–02. Also update the existing `flink-ui` Makefile target to print the browser URL instead of running `kubectl port-forward`.

## Discipline

devops — shell scripting, Helm, Makefile.

## Domain Entities

- **dev/setup-k8s.sh**: One-time bootstrap. Installs cert-manager v1.14.4 and Flink Operator v1.8.0 into Docker Desktop's built-in cluster via Helm. Idempotent — safe to run multiple times.
- **k8s-up**: Builds backend and frontend Docker images, then applies enterprise and controlplane manifests. Waits for pods to be ready.
- **k8s-down**: Deletes ns-enterprise and ns-controlplane namespaces. Does NOT delete tenant namespaces (preserves Flink job state for debugging).
- **k8s-status**: Shows pod readiness summary across ns-enterprise, ns-controlplane, and any tenant-* namespaces.
- **flink-ui**: Updated to print the Nginx proxy URL instead of running `kubectl port-forward`.

## Data Sources

- `dev/setup-kind.sh` — existing kind-based setup script, reference for Helm chart versions and values: cert-manager v1.14.4, Flink Operator v1.8.0 (`flink-kubernetes-operator`), MinIO Bitnami v14.6.0
- `Makefile` — existing targets: `build`, `up`, `infra`, `down`, `clean`, `logs`, `flink-ui`. Read before editing to understand current structure.
- `dev/k8s/enterprise/` (from unit-01) — manifests to apply for k8s-up
- `dev/k8s/controlplane/` (from unit-02) — manifests to apply for k8s-up

## Technical Specification

### dev/setup-k8s.sh

```bash
#!/bin/bash
set -e

echo "=== Setting up Flink Platform on Docker Desktop Kubernetes ==="

# Verify kubectl context is docker-desktop
CURRENT_CONTEXT=$(kubectl config current-context)
if [ "$CURRENT_CONTEXT" != "docker-desktop" ]; then
  echo "ERROR: kubectl context is '$CURRENT_CONTEXT', expected 'docker-desktop'"
  echo "Switch context: kubectl config use-context docker-desktop"
  exit 1
fi

# 1. Install cert-manager (required by Flink Operator webhook)
echo "Installing cert-manager v1.14.4..."
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.4/cert-manager.yaml
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=cert-manager \
  -n cert-manager --timeout=120s

# 2. Install Flink Kubernetes Operator via Helm
echo "Installing Flink Kubernetes Operator v1.8.0..."
helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.8.0/
helm repo update
helm upgrade --install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
  --namespace flink-operator \
  --create-namespace \
  --version 1.8.0 \
  --set webhook.create=true \
  --wait --timeout 120s

echo ""
echo "=== Setup complete! ==="
echo "Run 'make k8s-up' to start all platform services."
```

**MinIO is NOT installed by setup-k8s.sh** — MinIO is deployed as a regular Kubernetes Deployment in ns-enterprise (unit-01), not via Helm. This keeps all service manifests in `dev/k8s/enterprise/` for consistency.

### Makefile targets

Add the following to the existing Makefile. **Read the Makefile first** to find the right place to insert — add after the existing `flink-ui` target. Preserve all existing targets unchanged.

```makefile
# ── Kubernetes (Docker Desktop) ─────────────────────────────────────────────

## Build Docker images for K8s deployment (backend + frontend)
.PHONY: k8s-build
k8s-build:
	@echo "Building backend image..."
	docker build -t flinkaidlc-backend:latest .
	@echo "Building frontend image..."
	docker build -t flinkaidlc-frontend:latest ./frontend

## Bootstrap cluster (run once): installs cert-manager + Flink Operator
.PHONY: k8s-setup
k8s-setup:
	@bash dev/setup-k8s.sh

## Start all K8s services (builds images, applies manifests, waits for ready)
.PHONY: k8s-up
k8s-up: k8s-build
	@echo "Applying enterprise namespace manifests..."
	kubectl apply -f dev/k8s/enterprise/
	@echo "Waiting for enterprise services..."
	kubectl wait --for=condition=ready pod --all -n ns-enterprise --timeout=180s
	@echo "Applying controlplane namespace manifests..."
	kubectl apply -f dev/k8s/controlplane/
	@echo "Waiting for controlplane services..."
	kubectl wait --for=condition=ready pod --all -n ns-controlplane --timeout=120s
	@echo ""
	@echo "=== Platform is up! ==="
	@echo "Frontend:   http://localhost:30080"
	@echo "Kafka UI:   http://localhost:30080/kafka-ui/"
	@echo "Keycloak:   http://localhost:30080/realms/master"

## Stop K8s services (deletes ns-enterprise and ns-controlplane, preserves tenant namespaces)
.PHONY: k8s-down
k8s-down:
	@echo "Deleting ns-enterprise..."
	kubectl delete namespace ns-enterprise --ignore-not-found
	@echo "Deleting ns-controlplane..."
	kubectl delete namespace ns-controlplane --ignore-not-found
	@echo "Done. Tenant namespaces (tenant-*) preserved."

## Show pod status across all platform namespaces
.PHONY: k8s-status
k8s-status:
	@echo "=== ns-enterprise ==="
	@kubectl get pods -n ns-enterprise 2>/dev/null || echo "(namespace not found)"
	@echo ""
	@echo "=== ns-controlplane ==="
	@kubectl get pods -n ns-controlplane 2>/dev/null || echo "(namespace not found)"
	@echo ""
	@echo "=== Tenant namespaces ==="
	@kubectl get pods -A -l app.kubernetes.io/managed-by=flink-platform 2>/dev/null || echo "(no tenant pods)"
```

### Updated flink-ui target

Replace the existing `flink-ui` target (which runs `kubectl port-forward`) with:

```makefile
## Open Flink UI for a pipeline (usage: make flink-ui TENANT=my-tenant PIPELINE=pipeline-id)
.PHONY: flink-ui
flink-ui:
	@echo "Flink UI available at:"
	@echo "  http://localhost:30080/flink/$(TENANT)/$(PIPELINE)/"
	@echo ""
	@echo "If running Docker Compose (make up), port-forward manually:"
	@echo "  kubectl port-forward svc/pipeline-$(PIPELINE)-rest 8081:8081 -n tenant-$(TENANT)"
```

### Idempotency

`k8s-up` uses `kubectl apply` (not `kubectl create`) so it's safe to run multiple times. The `--ignore-not-found` flag on `k8s-down` ensures it doesn't fail if namespaces don't exist. `setup-k8s.sh` uses `helm upgrade --install` which is idempotent.

### Context guard

The `dev/setup-k8s.sh` script checks for `docker-desktop` context and exits if wrong. Consider adding the same guard to `k8s-up` and `k8s-down` to prevent accidental operations against a non-local cluster:

```makefile
K8S_CONTEXT := $(shell kubectl config current-context 2>/dev/null)
k8s-up k8s-down k8s-status: GUARD_K8S_CONTEXT = @if [ "$(K8S_CONTEXT)" != "docker-desktop" ]; then echo "ERROR: kubectl context is '$(K8S_CONTEXT)', expected 'docker-desktop'"; exit 1; fi
k8s-up: GUARD_K8S_CONTEXT
k8s-down: GUARD_K8S_CONTEXT
```

Or add a simple check at the top of each target — choose whichever approach matches the Makefile style.

## Success Criteria

- [ ] `bash dev/setup-k8s.sh` installs cert-manager v1.14.4 and Flink Operator v1.8.0 into Docker Desktop cluster without errors
- [ ] `make k8s-setup` calls the setup script successfully
- [ ] `make k8s-up` builds both images, applies all manifests, waits for readiness, and prints the access URLs
- [ ] Running `make k8s-up` a second time (idempotency) produces no errors
- [ ] `make k8s-down` deletes ns-enterprise and ns-controlplane; `kubectl get ns` shows they're gone
- [ ] `make k8s-status` shows pod status for all platform namespaces
- [ ] `make flink-ui TENANT=my-tenant PIPELINE=abc123` prints the correct Nginx proxy URL
- [ ] `make up` (Docker Compose) is unaffected — runs exactly as before
- [ ] Flink Operator CRD `flinkdeployments.flink.apache.org` exists in the cluster after setup: `kubectl get crd flinkdeployments.flink.apache.org`

## Risks

- **Helm repo URL for Flink Operator**: The Helm repository URL changes between Flink Operator versions. Verify the correct URL for v1.8.0 before hardcoding it. Alternative: use the OCI-based install if the standard Helm repo is unavailable.
- **cert-manager webhook startup delay**: After `kubectl apply`, cert-manager's webhook may not be ready immediately. The `kubectl wait` with `--timeout=120s` handles this, but on slow machines it may need more time. Mitigation: increase timeout to 180s if needed.
- **kubectl wait --all with mixed pod states**: `kubectl wait --for=condition=ready pod --all -n ns-enterprise` fails if ANY pod fails (not just times out). If a pod has a CrashLoopBackOff, the wait exits immediately with error. The error message should be clear enough for the developer to investigate. Mitigation: add a `|| kubectl get pods -n ns-enterprise` fallback so the developer sees pod state on failure.
- **Docker build context**: The backend `docker build -t flinkaidlc-backend:latest .` runs from the repo root. Verify the Dockerfile is at the root (not in a subdirectory). The frontend `docker build` target is `./frontend` — verify this is correct by reading the frontend Dockerfile path.
- **`make` variable syntax for TENANT/PIPELINE**: Makefile variables passed on the command line (`make flink-ui TENANT=x PIPELINE=y`) work differently from shell variables. Ensure `$(TENANT)` and `$(PIPELINE)` are referenced consistently.

## Boundaries

This unit does NOT handle:
- Writing K8s manifests (units 01–02)
- Spring Boot properties (unit-03)
- Flink SQL runner image build (separate concern, assumed pre-existing or documented as a separate step)
- CI/CD — this is local dev only
- Production cluster setup

## Notes

- The existing `dev/setup-kind.sh` targets a `kind` cluster. Do NOT modify it — `setup-k8s.sh` is a new file for Docker Desktop. Both can coexist.
- Docker Desktop Kubernetes uses the same Docker daemon as the local machine, so images built with `docker build` are immediately available to pods with `imagePullPolicy: Never`. No registry push needed.
- The Flink Operator Helm repo URL and chart version should match what's in `dev/setup-kind.sh` for consistency. Read that file first.
- If the Flink SQL runner image (`flink-sql-runner:latest`) doesn't exist in Docker yet, `k8s-up` should print a warning with instructions on how to build it. This image is used by FlinkDeploymentBuilder and must be built separately. Add a `k8s-build-flink-runner` target as a no-op stub with a comment explaining what it would do, or document the build steps in the startup output.
