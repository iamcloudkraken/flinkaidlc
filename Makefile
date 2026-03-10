.PHONY: up down logs build clean flink-ui infra

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

## Port-forward Flink Web UI for the first FlinkDeployment found (requires kind cluster)
flink-ui:
	@PIPELINE=$$(kubectl get flinkdeployment --all-namespaces -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
	NS=$$(kubectl get flinkdeployment --all-namespaces -o jsonpath='{.items[0].metadata.namespace}' 2>/dev/null); \
	if [ -z "$$PIPELINE" ]; then \
		echo "No FlinkDeployment found. Deploy a pipeline first (requires kind cluster from dev/setup-kind.sh)."; \
		exit 1; \
	fi; \
	echo "Port-forwarding Flink UI for $$PIPELINE in $$NS to http://localhost:8081 ..."; \
	kubectl port-forward "svc/$${PIPELINE}-rest" 8081:8081 -n "$$NS"

help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## //'
