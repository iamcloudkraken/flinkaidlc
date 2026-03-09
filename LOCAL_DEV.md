# Local Development Guide

This guide covers two modes for running the Flink SQL Pipeline Platform locally:

| Mode | Best for | K8s required? |
|------|----------|---------------|
| **Mode 1 — Docker Compose** | API and UI development | No |
| **Mode 2 — Native backend + Kind** | Full Flink pipeline deployment testing | Yes |

---

## Prerequisites

### Required for both modes
| Tool | Version | Verify |
|------|---------|--------|
| Java | 21+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker Desktop | 4.x+ | `docker info` |

### Required for Mode 2 only
| Tool | Install | Verify |
|------|---------|--------|
| kind | `brew install kind` | `kind version` |
| kubectl | `brew install kubectl` | `kubectl version --client` |
| Helm | `brew install helm` | `helm version` |

---

## Mode 1: Docker Compose Stack

Runs all 7 services (postgres, keycloak, kafka, zookeeper, schema-registry, backend, frontend) with a single command. No Kubernetes required.

### Start

```bash
make up
```

This builds the Spring Boot JAR (`mvn package -DskipTests`) then starts all containers.

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Frontend UI | http://localhost:3000 | dev@local.dev / dev123 |
| Backend API | http://localhost:8090/api/v1 | Any Bearer token |
| Keycloak admin | http://localhost:8080 | admin / admin |
| Schema Registry | http://localhost:8082 | — |
| PostgreSQL | localhost:5432 | flinkplatform / flinkplatform |

> **Tip:** The backend accepts **any Bearer token** in local mode (mock JWT decoder). Use `Authorization: Bearer dev-token` for curl commands — no real token needed.

### Pre-seeded data

On first `make up`, the backend automatically creates:
- **Tenant:** "Demo Org" (slug: `demo`, id: `00000000-0000-0000-0000-000000000001`)
- **Pipeline:** "Hello World Pipeline" (status: `DRAFT`)

### API smoke tests

```bash
# Check backend health
curl http://localhost:8090/actuator/health

# Get demo tenant (any bearer token works)
curl -H "Authorization: Bearer dev-token" \
  http://localhost:8090/api/v1/tenants/00000000-0000-0000-0000-000000000001

# List pipelines
curl -H "Authorization: Bearer dev-token" \
  http://localhost:8090/api/v1/pipelines
```

### Useful commands

```bash
make logs       # Tail backend + frontend logs
make down       # Stop containers (data preserved)
make clean      # Stop containers and delete all data volumes
make infra      # Start only infrastructure (postgres, keycloak, kafka) without backend/frontend
```

---

## Mode 2: Native Backend + Kind Cluster

Runs the backend natively on your machine against a local Kubernetes cluster. Required for full Flink pipeline deployment and the Flink Web UI.

### 1. Set up the kind cluster

```bash
./dev/setup-kind.sh
```

This installs:
- **cert-manager** v1.14.4
- **Flink Kubernetes Operator** v1.8.0
- **MinIO** (local S3 for checkpoints/savepoints)

Takes ~5 minutes on first run.

### 2. Start infrastructure services

```bash
make infra
# Starts: postgres, keycloak, kafka, zookeeper, schema-registry
```

### 3. Run the backend natively

```bash
SPRING_PROFILES_ACTIVE=local \
K8S_PROVISIONER_ENABLED=true \
FLINK_S3_ENDPOINT=http://localhost:9000 \
FLINK_S3_ACCESS_KEY=minioadmin \
FLINK_S3_SECRET_KEY=minioadmin \
mvn spring-boot:run
```

> **Note:** The backend must run natively (not in Docker) to access the kind cluster API via `~/.kube/config`. The backend inside Docker cannot reach the kind cluster.

### 4. Run the frontend (optional)

```bash
cd frontend
npm install
npm run dev
# Open http://localhost:5173
```

### 5. Deploy a pipeline

1. Open the UI at http://localhost:5173 (or http://localhost:3000 if using Docker)
2. Log in as `dev@local.dev` / `dev123`
3. Navigate to **Pipelines → New Pipeline**
4. Complete the 5-step wizard and click **Create Pipeline**
5. The pipeline will be created in `DRAFT` status

### 6. View Flink Web UI

```bash
make flink-ui
# Port-forwards Flink UI for the first FlinkDeployment to http://localhost:8081
```

> The Flink Web UI is per-pipeline (one JobManager per FlinkDeployment). Run `make flink-ui` after deploying a pipeline.

### Service URLs (Mode 2)

| Service | URL |
|---------|-----|
| Frontend | http://localhost:5173 |
| Backend API | http://localhost:8090/api/v1 |
| MinIO S3 API | http://localhost:9000 |
| MinIO Console | http://localhost:9090 (minioadmin / minioadmin) |
| Flink Web UI | http://localhost:8081 (after `make flink-ui`) |

### Tear down kind cluster

```bash
./dev/teardown-kind.sh
```

---

## Running Tests

### Unit tests (no Docker required)

```bash
mvn test
```

### Integration tests (Testcontainers — Docker required, no pre-running services needed)

```bash
mvn verify
# Testcontainers auto-starts PostgreSQL for the duration of the test run
```

### Run a specific test class

```bash
mvn test -Dtest=PipelineControllerIntegrationTest
```

---

## Troubleshooting

### Backend fails to start: `Connection refused` to PostgreSQL

```
Ensure postgres is running:
  docker compose ps postgres
  # Should show "healthy"

Check port 5432 is not in use by another process:
  lsof -i :5432
```

### Backend fails to start: JWT configuration error

```
Ensure SPRING_PROFILES_ACTIVE=local is set.
The local profile provides a mock JWT decoder that bypasses Keycloak.

If running with Mode 2 and you want real Keycloak JWTs:
  Ensure keycloak is running (docker compose up -d keycloak) and
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://localhost:8080/realms/flink-platform
```

### "SSRF protection" error when creating a pipeline with schema-registry URL

```
Ensure SPRING_PROFILES_ACTIVE=local — the SSRF check is bypassed in local profile.
Use these schema registry URLs:
  Mode 1 (Docker): http://schema-registry:8082
  Mode 2 (native): http://localhost:8082
```

### Flink pipeline stays in DEPLOYING indefinitely

```
Check Flink Operator logs:
  kubectl logs -n flink-operator deploy/flink-kubernetes-operator -f

Check FlinkDeployment status:
  kubectl describe flinkdeployment -n tenant-demo

Check MinIO is accessible:
  curl http://localhost:9000/minio/health/live
```

### Port 8080 conflict (Keycloak)

Keycloak runs on port 8080. If another service uses that port, change it in `docker-compose.yml`:

```yaml
keycloak:
  ports:
    - "8180:8080"  # Expose on 8180 instead
```

Then update the backend environment variable:
```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://keycloak:8080/realms/flink-platform
```
(The internal Docker URL stays the same — only the host port changes.)

### `make up` fails: `target/*.jar not found`

```bash
# Build the JAR manually first:
mvn clean package -DskipTests

# Then start:
docker compose up -d --build
```

### Keycloak startup takes too long

Keycloak 24 with `start-dev` can take 60-90 seconds. The backend `depends_on` Keycloak's healthcheck, so it will wait automatically. If the timeout is exceeded:

```bash
# Check Keycloak logs:
docker compose logs keycloak

# Restart Keycloak:
docker compose restart keycloak
```
