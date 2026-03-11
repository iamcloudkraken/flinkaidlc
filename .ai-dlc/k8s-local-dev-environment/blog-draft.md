# From Docker Compose to Kubernetes: A Better Local Dev Experience for Flink Pipelines

## The Problem

Our Flink SQL Pipeline Platform lets teams define real-time data pipelines in SQL without writing Java or managing Kubernetes directly. But for the developers building the platform itself, local development was stuck in a halfway house.

We had Docker Compose — which worked well for running backend, frontend, Keycloak, and Kafka together — but it had hard limits:

- **No Kubernetes.** The `TenantNamespaceProvisioner` creates a Kubernetes namespace, ServiceAccount, RBAC, and NetworkPolicy for each tenant. None of that was testable locally without spinning up a separate kind cluster and running the backend natively (outside Docker, so it could reach the cluster API).
- **No namespace isolation.** All services ran flat, no separation between enterprise infrastructure and the control plane.
- **Manual port-forwarding for every pipeline.** The `make flink-ui` target ran `kubectl port-forward` — you had to run it per pipeline, pick a port, and remember to tear it down.

## What We Built

We added a Kubernetes-native local dev mode on top of Docker Desktop's built-in single-node cluster. The new mode replicates the production namespace topology without requiring an external cluster or an image registry.

### Namespace topology

```
ns-enterprise      Kafka, Zookeeper, Schema Registry, Keycloak, MinIO, Kafka UI
ns-controlplane    PostgreSQL, backend (Spring Boot), frontend (Nginx), Nginx Flink proxy
tenant-{slug}      Flink jobs — provisioned dynamically by TenantNamespaceProvisioner
```

This is the same structure as production. Testing tenant onboarding locally now creates a real `tenant-{slug}` namespace with the correct RBAC and NetworkPolicy — the same code path that runs in production.

### Getting started

```bash
# One-time cluster bootstrap (installs cert-manager + Flink Kubernetes Operator)
make k8s-setup

# Start everything
make k8s-up
```

`make k8s-up` builds the backend and frontend Docker images, applies all manifests in order, and waits for pod readiness before printing the access URLs:

```
=== Platform is up! ===
  Frontend:   http://localhost:30080
  Kafka UI:   http://localhost:30080/kafka-ui/
  Keycloak:   http://localhost:30080/realms/master
```

### No port-forwarding for Flink UIs

The old `make flink-ui` command ran `kubectl port-forward` for the first FlinkDeployment it found — one pipeline, one port, manual teardown. With multiple concurrent pipelines across different tenant namespaces, this didn't scale.

The new setup includes an Nginx Flink proxy in `ns-controlplane`. It routes:

```
/flink/{tenant_slug}/{pipeline_id}/  →  pipeline-{pipeline_id}-rest.tenant-{tenant_slug}.svc.cluster.local:8081
```

Every Flink job's web UI is accessible through a single NodePort at `http://localhost:30080`. The updated `make flink-ui` target just prints the URL:

```bash
make flink-ui TENANT=acme PIPELINE=abc123
# → http://localhost:30080/flink/acme/abc123/
```

### Docker Compose still works

The existing `make up` workflow is completely unchanged. Both modes coexist and can be used independently. If you're doing backend feature work that doesn't touch Kubernetes behaviour, Docker Compose is still the faster option.

## Implementation notes

**No image registry needed.** Docker Desktop's Kubernetes cluster uses the same Docker daemon as the host. Images built with `docker build` are immediately available to pods with `imagePullPolicy: Never`. No `docker push`, no local registry.

**The `local-k8s` Spring profile** is additive to `local`. It enables `k8s.provisioner.enabled=true`, points Kafka and the database at in-cluster DNS names, and sets `kubernetes.platform-namespace=ns-controlplane`. The mock JWT decoder (blank issuer-uri) is inherited from the `local` profile — Keycloak still runs in the cluster, but the backend doesn't need to reach it for token validation in dev.

**NetworkPolicy update.** `TenantNamespaceProvisioner` creates a NetworkPolicy in each tenant namespace that allows ingress from the platform's namespace. The namespace label was `flink-platform` in the old Docker Compose config; it's now `ns-controlplane` to match the actual Kubernetes namespace name.

**Idempotency.** `make k8s-up` uses `kubectl apply` throughout. `dev/setup-k8s.sh` uses `helm upgrade --install`. Running either command twice produces no errors.

## Outcome

- `make k8s-setup` + `make k8s-up` gives you a full Kubernetes-backed platform in minutes
- Tenant provisioning, Flink job deployment, and the Nginx Flink proxy are testable locally without an external cluster
- The Flink web UI for any pipeline is one URL away, no port-forwarding required
- Docker Compose local dev is unaffected

The change in developer workflow is small. The improvement in how closely local testing mirrors production is significant.
