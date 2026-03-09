---
status: completed
depends_on:
  - unit-01-spring-local-profile
  - unit-02-docker-compose-stack
  - unit-03-kind-cluster-setup
branch: ai-dlc/local-dev-environment/04-local-dev-docs
discipline: documentation
workflow: ""
ticket: ""
---

# unit-04-local-dev-docs

## Description

Write `LOCAL_DEV.md` in the project root that documents two local development modes: (1) Docker Compose full-stack (no Kubernetes) for UI/API development, and (2) native backend with kind cluster for full Flink pipeline deployment testing. The guide must be completable by a new developer in under 10 minutes from a clean machine.

## Discipline

documentation - This unit will be executed by `do-documentation` specialized agents.

## Domain Entities

- **Tenant** — document that the seed demo tenant is pre-created with slug `demo`, and how to register a new one via the UI
- **Pipeline** — document how to create a pipeline via the 5-step UI editor and how to deploy it to Flink (kind mode only)

## Data Sources

- `docker-compose.yml` (from unit-02) — service names, ports, health endpoint
- `Makefile` (from unit-02) — `make up`, `make down`, `make logs`, `make clean`, `make flink-ui`
- `dev/setup-kind.sh` (from unit-03) — prerequisites, what it installs
- `src/main/resources/application-local.properties` (from unit-01) — config keys
- `docker/keycloak/realm-export.json` (from unit-02) — test user credentials

## Technical Specification

### `LOCAL_DEV.md` structure

```
# Local Development Guide

## Modes
- Mode 1: Docker Compose (no K8s) — for API/UI development
- Mode 2: Native backend + Kind cluster — for full Flink pipeline testing

## Prerequisites
### Required for both modes
- Java 21 (verify: java -version)
- Maven 3.9+ (verify: mvn -version)
- Docker Desktop 4.x+ (verify: docker info)

### Required for Mode 2 only
- kind (verify: kind version)
- kubectl (verify: kubectl version --client)
- Helm 3 (verify: helm version)

## Mode 1: Docker Compose Stack

### Start everything
make up
# → builds JAR, starts 7 containers, seeds demo data

### Service URLs
| Service | URL | Notes |
|---------|-----|-------|
| Frontend | http://localhost:3000 | React UI |
| Backend API | http://localhost:8090/api/v1 | REST API |
| Keycloak | http://localhost:8080 | OAuth2 server |
| PostgreSQL | localhost:5432 | DB: flinkplatform |
| Schema Registry | http://localhost:8082 | Avro schema validation |
| MinIO Console | N/A (Mode 2 only) | |

### Login
- URL: http://localhost:3000
- Username: dev@local.dev
- Password: dev123
- The UI uses Keycloak for auth; the backend accepts any valid JWT from the local Keycloak

### Pre-seeded data
On first `make up`, the backend seeds:
- Tenant: "Demo Org" (slug: demo)
- Pipeline: "Hello World Pipeline" (DRAFT status)

### API smoke tests (with curl)
# Get demo tenant
curl -H "Authorization: Bearer dev-token" http://localhost:8090/api/v1/tenants/00000000-0000-0000-0000-000000000001

# List pipelines
curl -H "Authorization: Bearer dev-token" http://localhost:8090/api/v1/pipelines

### Stopping
make down          # stop containers, keep data
make clean         # stop containers, delete all data volumes

### Logs
make logs          # tail backend + frontend logs

## Mode 2: Native Backend + Kind Cluster (full Flink)

### 1. Set up the kind cluster
./dev/setup-kind.sh
# Installs: cert-manager, Flink Kubernetes Operator 1.8.0, MinIO

### 2. Run the backend natively (NOT in Docker Compose)
# First, start only the infrastructure services:
docker compose up -d postgres keycloak kafka zookeeper schema-registry

# Then run backend with K8s enabled:
SPRING_PROFILES_ACTIVE=local \
K8S_PROVISIONER_ENABLED=true \
FLINK_S3_ENDPOINT=http://localhost:9000 \
FLINK_S3_ACCESS_KEY=minioadmin \
FLINK_S3_SECRET_KEY=minioadmin \
mvn spring-boot:run

### 3. Run the frontend
cd frontend && npm install && npm run dev
# Frontend available at http://localhost:5173

### 4. Create and deploy a pipeline
1. Open http://localhost:5173
2. Log in as dev@local.dev / dev123
3. Navigate to Pipelines → New Pipeline
4. Fill in the 5-step wizard (Step 1: name, Step 2: source, Step 3: sink, Step 4: SQL, Step 5: review)
5. Click "Create Pipeline" → pipeline created in DRAFT
6. On the Pipeline Detail page, click "Deploy" (if that button exists) OR use the API:
   curl -X POST -H "Authorization: Bearer <token>" http://localhost:8090/api/v1/pipelines/<id>/deploy

### 5. View Flink Web UI
make flink-ui     # port-forwards the first FlinkDeployment to localhost:8081
# Then open http://localhost:8081

### Tear down kind cluster
./dev/teardown-kind.sh

## Troubleshooting

### Backend fails to start: "Connection refused" (PostgreSQL)
→ Ensure Docker Compose postgres is running: docker compose ps postgres
→ Ensure port 5432 is not in use: lsof -i :5432

### Backend fails to start: JWT configuration error
→ In Mode 1 (Docker Compose), the backend uses the mock JwtDecoder — ensure SPRING_PROFILES_ACTIVE=local
→ If running natively without Keycloak, ensure oauth2.admin.url is blank in application-local.properties

### "SSRF protection" error when creating a pipeline with schema-registry URL
→ Ensure SPRING_PROFILES_ACTIVE=local — the SSRF check is bypassed in local profile only
→ Use the service name URL: http://schema-registry:8082 (Mode 1) or http://localhost:8082 (Mode 2)

### Flink pipeline stays in DEPLOYING indefinitely
→ Check Flink Operator logs: kubectl logs -n flink-operator deploy/flink-kubernetes-operator -f
→ Check FlinkDeployment status: kubectl describe flinkdeployment -n tenant-demo

### Port 8080 conflict (Keycloak)
→ If another service uses 8080, change Keycloak port in docker-compose.yml:
   ports: ["8180:8080"]  # Expose on 8180 instead
   Then update SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI to use port 8180

### make up fails: "target/*.jar not found"
→ Build the JAR manually: mvn clean package -DskipTests
→ Then: docker compose up -d --build

## Running Tests

### Unit tests only (no Docker required)
mvn test

### Integration tests (Testcontainers — Docker required, no pre-running services needed)
mvn verify
# Testcontainers auto-starts postgres in a container for the duration of the test

### Run a specific test class
mvn test -pl . -Dtest=PipelineControllerIntegrationTest
```

### Length and tone requirements

- Document must be completable in ≤10 minutes for a developer who has the prerequisites installed
- Use concrete commands (no "run the command" vague language)
- Each section starts with the exact command to run, followed by explanation
- Troubleshooting section must cover the top 5 known failure modes (from the discovery findings: PostgreSQL connection, JWT config, SSRF error, Flink stuck, port collision)
- Do NOT document Kubernetes internals beyond what's needed to run the scripts

## Success Criteria

- [ ] `LOCAL_DEV.md` exists at the project root
- [ ] Mode 1 section: a developer can get from `git clone` to `make up` to accessing the UI with only the commands in the doc (no external research needed)
- [ ] Mode 2 section: documents the limitation that backend must run natively (not in Docker Compose) for kind cluster access
- [ ] All service URLs are listed in a table with correct ports
- [ ] Troubleshooting covers: PostgreSQL connection failure, JWT config error, SSRF error, Flink stuck in DEPLOYING, port 8080 collision
- [ ] `make flink-ui` command is documented with its expected output

## Risks

- **Docs drift**: If unit-01/02/03 builders change ports or file names, the docs will be wrong. Mitigation: Reviewer hat (after all 4 units complete) verifies docs against actual files.
- **Prerequisites not installed**: The doc lists prerequisites but cannot guarantee they're installed. Mitigation: Each prerequisite has a verify command so the developer can confirm before proceeding.

## Boundaries

This unit does NOT:
- Write any code or configuration files (units 01–03 do that)
- Write API reference documentation (separate concern)
- Document CI/CD or production deployment

## Notes

- `LOCAL_DEV.md` goes in the **project root** (same level as `pom.xml`), not in `docs/`
- The mock JWT decoder in Mode 1 means `Authorization: Bearer dev-token` works as a curl test — this is intentional and should be mentioned as a convenience
- Mode 2 documents `npm run dev` for the frontend (Vite dev server on port 5173) rather than the Docker nginx, since Mode 2 targets active frontend development

