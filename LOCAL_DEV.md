# Local Development Guide

This guide covers three modes for running the Flink SQL Pipeline Platform locally:

| Mode | Best for | K8s required? | Spring profile |
|------|----------|---------------|----------------|
| **Mode 1 — Docker Compose** | API and UI development | No | `local` |
| **Mode 2 — Native backend + Kind** | Full Flink pipeline deployment testing (kind cluster) | Yes | `local` |
| **Mode 3 — Docker Desktop Kubernetes** | Full Flink pipeline deployment testing (Docker Desktop cluster) | Yes | `local-k8s` |

**Quick pick:**
- Day-to-day backend/frontend work → **Mode 1**
- Testing `TenantNamespaceProvisioner`, Flink job lifecycle, or the Nginx Flink proxy → **Mode 3**
- Already have a kind cluster set up → **Mode 2**

---

## Prerequisites

### Required for all modes
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

### Required for Mode 3 only
| Tool | Install | Verify |
|------|---------|--------|
| kubectl | `brew install kubectl` | `kubectl version --client` |
| Helm | `brew install helm` | `helm version` |
| Docker Desktop Kubernetes | Enable in Docker Desktop → Settings → Kubernetes | `kubectl config use-context docker-desktop` |

---

## Mode 1: Docker Compose Stack

Runs all services (postgres, keycloak, kafka, zookeeper, schema-registry, backend, frontend) with a single command. No Kubernetes required.

### Start

```bash
make up
```

This builds the Spring Boot JAR (`mvn package -DskipTests`) then starts all containers.

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Frontend UI | http://localhost:3100 | dev@local.dev / dev123 |
| Backend API | http://localhost:8191/api/v1 | Any Bearer token |
| Keycloak admin | http://localhost:8180 | admin / admin |
| Schema Registry | http://localhost:8182 | — |
| PostgreSQL | localhost:5433 | flinkplatform / flinkplatform |

> **Note:** Ports are offset to avoid conflicts with a co-running `local-dev-environment` stack (which occupies 3000, 8090, 8080, 8082, 5432).

> **Tip:** The backend accepts **any Bearer token** in local mode (mock JWT decoder). Use `Authorization: Bearer dev-token` for curl commands — no real token needed.

### Pre-seeded data

On first `make up`, the backend automatically creates:
- **Tenant:** "Demo Org" (slug: `demo`, id: `00000000-0000-0000-0000-000000000001`)
- **Pipeline:** "Hello World Pipeline" (status: `DRAFT`)

### API smoke tests

```bash
# Check backend health
curl http://localhost:8191/actuator/health

# Get demo tenant (any bearer token works)
curl -H "Authorization: Bearer dev-token" \
  http://localhost:8191/api/v1/tenants/00000000-0000-0000-0000-000000000001

# List pipelines
curl -H "Authorization: Bearer dev-token" \
  http://localhost:8191/api/v1/pipelines
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

Runs the backend natively on your machine against a local `kind` Kubernetes cluster. Required for full Flink pipeline deployment and the Flink Web UI.

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

1. Open the UI at http://localhost:5173
2. Log in as `dev@local.dev` / `dev123`
3. Navigate to **Pipelines → New Pipeline**
4. Complete the wizard and click **Create Pipeline**

### 6. View Flink Web UI

```bash
make flink-ui
# Port-forwards Flink UI for the first FlinkDeployment to http://localhost:8081
```

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

## Mode 3: Docker Desktop Kubernetes

Runs the entire platform — backend, frontend, and all infrastructure — as Kubernetes workloads on Docker Desktop's built-in single-node cluster. This is the closest local topology to production:

- `ns-enterprise` — Kafka, Zookeeper, Schema Registry, Keycloak, MinIO, Kafka UI
- `ns-controlplane` — PostgreSQL, backend (Spring Boot), frontend (Nginx), Nginx Flink proxy
- `tenant-{slug}` — Flink jobs, provisioned dynamically by `TenantNamespaceProvisioner`

No image registry needed — Docker Desktop shares the Docker daemon so locally-built images are immediately available to pods.

### 1. Enable Kubernetes in Docker Desktop

Docker Desktop → Settings → Kubernetes → Enable Kubernetes → Apply & Restart

Switch kubectl context:

```bash
kubectl config use-context docker-desktop
```

### 2. Bootstrap the cluster (once)

```bash
make k8s-setup
```

This installs:
- **cert-manager** v1.14.4 (required by the Flink Operator webhook)
- **Flink Kubernetes Operator** v1.8.0

Takes ~3–5 minutes. Safe to re-run — `helm upgrade --install` is idempotent.

### 3. Start the platform

```bash
make k8s-up
```

This:
1. Builds backend and frontend Docker images (`docker build`)
2. Applies `dev/k8s/enterprise/` manifests and waits for all pods to be ready
3. Applies `dev/k8s/controlplane/` manifests and waits for all pods to be ready
4. Prints access URLs

```
=== Platform is up! ===
  Frontend:   http://localhost:30080
  Kafka UI:   http://localhost:30080/kafka-ui/
  Keycloak:   http://localhost:30080/realms/master
```

### 4. Configure the Spring profile

The backend in Mode 3 uses the `local-k8s` profile (automatically set in the K8s deployment manifest). It activates:
- `k8s.provisioner.enabled=true` — enables `TenantNamespaceProvisioner`
- In-cluster DNS for Kafka, PostgreSQL, Schema Registry
- `kubernetes.platform-namespace=ns-controlplane`
- Mock JWT decoder (same as `local` — no Keycloak JWT validation)

You can also run the backend natively against the Docker Desktop cluster:

```bash
SPRING_PROFILES_ACTIVE=local,local-k8s mvn spring-boot:run
```

### 5. Deploy a demo pipeline

Pre-seeded demo topics and schemas are created automatically by the `demo-seed` Job when the cluster starts:

| | Name | Schema subject |
|-|------|----------------|
| **Source topic** | `demo.events` | `demo.events-value` (`ClickEvent`) |
| **Sink topic** | `demo.enriched-events` | `demo.enriched-events-value` (`EnrichedClickEvent`) |

Use these in the Flink SQL wizard:

```
Bootstrap servers:   kafka.ns-enterprise.svc.cluster.local:29092
Schema Registry URL: http://schema-registry.ns-enterprise.svc.cluster.local:8081
Source topic:        demo.events
Sink topic:          demo.enriched-events
Format:              avro-confluent
```

Steps:

1. Open the UI at http://localhost:30080
2. Log in as `dev@local.dev` / `dev123` (pre-filled)
3. Register a tenant with slug `10001` (or any 5-digit number)
4. Navigate to **Pipelines → New Pipeline**
5. Complete the wizard using the demo topic details above and click **Create Pipeline**
6. A `FlinkDeployment` CRD is created in `tenant-10001` namespace

### 6. View Flink Web UI

No port-forwarding needed. The Nginx Flink proxy routes requests automatically.

For the demo pipeline, the URL pattern is:

```
http://localhost:30080/flink/{tenant-slug}/{pipeline-id}/
```

Example for tenant `10001` with pipeline ID `abc123`:

```
http://localhost:30080/flink/10001/abc123/
```

Get the pipeline ID from the UI or:

```bash
kubectl get flinkdeployment -n tenant-10001
# NAME       JOB STATUS   LIFECYCLE STATE
# abc123     RUNNING      STABLE
```

Then open:

```
http://localhost:30080/flink/10001/abc123/
```

Or use the helper:

```bash
make flink-ui TENANT=10001 PIPELINE=abc123
# Prints: http://localhost:30080/flink/10001/abc123/
```

### Service URLs (Mode 3)

| Service | URL | Credentials |
|---------|-----|-------------|
| Frontend UI | http://localhost:30080 | dev@local.dev / dev123 |
| Backend API | http://localhost:30080/api/v1 | Any Bearer token |
| Keycloak admin | http://localhost:30080/realms/master | admin / admin |
| Kafka UI | http://localhost:30080/kafka-ui/ | — |
| Flink UI | http://localhost:30080/flink/{tenant}/{pipeline}/ | — |
| MinIO (in-cluster) | minio.ns-enterprise.svc.cluster.local:9000 | minioadmin / minioadmin |

### Useful commands

```bash
make k8s-status                              # Pod readiness across all namespaces
make k8s-down                                # Delete ns-enterprise and ns-controlplane
                                             # (tenant-* namespaces preserved for debugging)
make k8s-up                                  # Idempotent — safe to re-run after changes
make flink-ui TENANT=10001 PIPELINE=abc123   # Print Flink UI URL
```

### Rebuilding after code changes

```bash
make k8s-up   # Rebuilds images and re-applies manifests (kubectl apply is idempotent)
```

If you changed the **flink-sql-runner** Dockerfile (e.g. added the S3 plugin) and pods still fail with "Could not find a file system implementation for scheme 's3'", force a clean rebuild so the new image is used:

```bash
docker build --no-cache -t flinkaidlc-flink-sql-runner:latest docker/flink-sql-runner
# Then delete the FlinkDeployment so the operator recreates pods with the new image:
kubectl delete flinkdeployment -n tenant-10001 -l app.kubernetes.io/managed-by=flink-platform
# Redeploy the pipeline from the UI (or let the platform recreate it).
```

For a rolling restart of a single service without rebuilding:

```bash
kubectl rollout restart deployment/backend -n ns-controlplane
kubectl rollout restart deployment/frontend -n ns-controlplane
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

### Test tenant ID

Integration tests use a fixed tenant ID injected via `TestSecurityConfig`:

```
00000000-0000-0000-0000-000000000001
```

This ID is embedded in the mock JWT `tenant_id` claim so authenticated endpoints resolve to the correct tenant without a real Keycloak token.

---

## Troubleshooting

### Backend fails to start: `Connection refused` to PostgreSQL

```
Ensure postgres is running:
  docker compose ps postgres       # Mode 1/2
  kubectl get pods -n ns-controlplane  # Mode 3

Check port 5433 is not in use:
  lsof -i :5433
```

### Backend fails to start: JWT configuration error

```
Ensure SPRING_PROFILES_ACTIVE=local (Mode 1/2) or local,local-k8s (Mode 3 native).
The local profile provides a mock JWT decoder that bypasses Keycloak.
```

### "SSRF protection" error when creating a pipeline with schema-registry URL

```
Ensure SPRING_PROFILES_ACTIVE includes local — the SSRF check is bypassed in local profile.

Schema Registry URLs by mode:
  Mode 1 (Docker Compose): http://schema-registry:8082
  Mode 2 (native):         http://localhost:8082
  Mode 3 (K8s):            http://schema-registry.ns-enterprise.svc.cluster.local:8081
```

### Flink pipeline stays in DEPLOYING indefinitely / flink-main-container CrashLoopBackOff

Often caused by the job-manager pod failing to reach S3 (MinIO) for checkpoints. The backend must inject `s3.endpoint` and credentials into the FlinkDeployment when using MinIO (Mode 3). Ensure `application-local-k8s.properties` has:

- `flink.state.s3-bucket=flink-local` (bucket created by minio-init)
- `flink.s3.endpoint=http://minio.ns-enterprise.svc.cluster.local:9000`
- `flink.s3.access-key=minioadmin` and `flink.s3.secret-key=minioadmin`

Then redeploy the pipeline (delete the FlinkDeployment and create again, or restart the backend so new deployments get the config).

```bash
# Inspect the failing pod (replace namespace and pod name)
kubectl logs -n tenant-10001 <pod-name> -c flink-main-container --previous

# Check Flink Operator logs
kubectl logs -n flink-operator deploy/flink-kubernetes-operator -f

# Check FlinkDeployment status
kubectl describe flinkdeployment -n tenant-10001

# Check MinIO is accessible (Mode 2)
curl http://localhost:9000/minio/health/live

# Check MinIO pod (Mode 3)
kubectl get pods -n ns-enterprise -l app=minio
```

### Mode 3: `make k8s-up` fails — pod not ready

```bash
# See pod events for the failing pod
kubectl describe pod -n ns-enterprise <pod-name>
kubectl describe pod -n ns-controlplane <pod-name>

# Check image was built
docker images | grep flinkaidlc

# Force rebuild and re-apply
make k8s-up
```

### Mode 3: `kubectl context is not docker-desktop`

```bash
kubectl config get-contexts          # list available contexts
kubectl config use-context docker-desktop
```

### Mode 3: Flink UI returns 502 Bad Gateway

The Nginx Flink proxy can't reach the pipeline's REST service. Check:

```bash
# Is the FlinkDeployment running?
kubectl get flinkdeployment -n tenant-{slug}

# Is the REST service created?
kubectl get svc -n tenant-{slug}
# Expected: pipeline-{pipeline-id}-rest

# Check Nginx Flink proxy logs
kubectl logs -n ns-controlplane deploy/nginx-flink-proxy
```

### Port conflicts with other stacks

The `docker-compose.yml` uses offset ports to avoid conflicts with a co-running `local-dev-environment` stack:

| Service | Host Port |
|---------|-----------|
| Frontend | 3100 |
| Backend | 8191 |
| Keycloak | 8180 |
| Schema Registry | 8182 |
| PostgreSQL | 5433 |
| Kafka | 9192 |
| Zookeeper | 2182 |

Mode 3 uses a single NodePort `30080` for all traffic (routed via Nginx).

### `make up` fails: `target/*.jar not found`

```bash
# Build the JAR manually first:
mvn clean package -DskipTests

# Then start:
docker compose up -d --build
```

### Keycloak startup takes too long

Keycloak 24 with `start-dev` can take 60–90 seconds. The backend `depends_on` Keycloak's healthcheck, so it will wait automatically. If the timeout is exceeded:

```bash
# Check Keycloak logs:
docker compose logs keycloak   # Mode 1/2
kubectl logs -n ns-enterprise deploy/keycloak   # Mode 3

# Restart Keycloak:
docker compose restart keycloak   # Mode 1/2
kubectl rollout restart deployment/keycloak -n ns-enterprise   # Mode 3
```
