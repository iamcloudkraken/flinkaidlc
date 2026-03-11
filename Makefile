.PHONY: up down logs build clean flink-ui infra k8s-setup k8s-build k8s-up k8s-down k8s-status

## Build the JAR then start all services
up: build
	docker compose up -d --build
	@echo ""
	@echo "Services starting... check status with: docker compose ps"
	@echo ""
	@echo "  Frontend:        http://localhost:3100"
	@echo "  Backend API:     http://localhost:8191/api/v1"
	@echo "  Keycloak:        http://localhost:8180  (admin/admin)"
	@echo "  Schema Registry: http://localhost:8182"
	@echo ""
	@echo "Login: dev@local.dev / dev123"

## Build the Spring Boot JAR (skips tests)
build:
	mvn clean package -DskipTests -q

## Start only infrastructure (postgres, keycloak, kafka, schema-registry) without backend/frontend
infra:
	docker compose up -d postgres keycloak zookeeper kafka schema-registry
	@echo "Infrastructure services started."
	@echo "Run backend natively: SPRING_PROFILES_ACTIVE=local mvn spring-boot:run"

## Stop all containers (keeps data volumes)
down:
	docker compose down

## Stop all containers and delete all data volumes
clean:
	docker compose down -v
	mvn clean -q

## Tail backend and frontend logs
logs:
	docker compose logs -f backend frontend

## Open Flink UI for a pipeline (usage: make flink-ui TENANT=my-tenant PIPELINE=pipeline-id)
flink-ui:
	@echo "Flink UI available at:"
	@echo "  http://localhost:30080/flink/$(TENANT)/$(PIPELINE)/"
	@echo ""
	@echo "If running Docker Compose (make up), port-forward manually:"
	@echo "  kubectl port-forward svc/pipeline-$(PIPELINE)-rest 8081:8081 -n tenant-$(TENANT)"

# ── Kubernetes (Docker Desktop) ─────────────────────────────────────────────

K8S_CONTEXT := $(shell kubectl config current-context 2>/dev/null)

.PHONY: k8s-guard
k8s-guard:
	@if [ "$(K8S_CONTEXT)" != "docker-desktop" ]; then \
		echo "ERROR: kubectl context is '$(K8S_CONTEXT)', expected 'docker-desktop'"; \
		echo "Switch context: kubectl config use-context docker-desktop"; \
		exit 1; \
	fi
	@if ! kubectl cluster-info --request-timeout=10s >/dev/null 2>&1; then \
		echo ""; \
		echo "ERROR: Cannot connect to the Kubernetes API server."; \
		echo ""; \
		echo "Ensure Docker Desktop Kubernetes is running:"; \
		echo "  Docker Desktop → Settings → Kubernetes → 'Kubernetes is running' (green)"; \
		echo ""; \
		echo "If it just started, wait 30-60 seconds and retry."; \
		exit 1; \
	fi

## Bootstrap cluster (run once): installs cert-manager + Flink Operator
.PHONY: k8s-setup
k8s-setup:
	@bash dev/setup-k8s.sh

## Build Docker images for K8s deployment (backend + frontend)
.PHONY: k8s-build
k8s-build:
	@echo "Building backend image..."
	docker build -f docker/backend/Dockerfile -t flinkaidlc-backend:latest .
	@echo "Building frontend image..."
	docker build -t flinkaidlc-frontend:latest ./frontend

## Start all K8s services (builds images, applies manifests, waits for ready)
.PHONY: k8s-up
k8s-up: k8s-guard k8s-build
	@echo "Applying enterprise namespace manifests..."
	kubectl apply -f dev/k8s/enterprise/
	@echo "Waiting for enterprise services to be ready..."
	kubectl wait --for=condition=ready pod --all -n ns-enterprise --timeout=180s || kubectl get pods -n ns-enterprise
	@echo "Applying controlplane namespace manifests..."
	kubectl apply -f dev/k8s/controlplane/
	@echo "Waiting for controlplane services to be ready..."
	kubectl wait --for=condition=ready pod --all -n ns-controlplane --timeout=120s || kubectl get pods -n ns-controlplane
	@echo ""
	@echo "=== Platform is up! ==="
	@echo "  Frontend:   http://localhost:30080"
	@echo "  Kafka UI:   http://localhost:30080/kafka-ui/"
	@echo "  Keycloak:   http://localhost:30080/realms/master"
	@echo ""
	@echo "Login: dev@local.dev / dev123"

## Stop K8s services (deletes ns-enterprise and ns-controlplane, preserves tenant namespaces)
.PHONY: k8s-down
k8s-down: k8s-guard
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

help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## //'
