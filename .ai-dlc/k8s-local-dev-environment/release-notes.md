# Release Notes: Kubernetes Local Dev Environment

## What's New

You can now run the full Flink SQL Pipeline Platform on Kubernetes locally — without needing an external cluster, a registry, or manual port-forwarding.

### Run on Kubernetes in three commands

```bash
make k8s-setup   # one-time: installs cert-manager + Flink Operator (Docker Desktop)
make k8s-up      # build images, apply manifests, wait for readiness
# → Frontend:  http://localhost:30080
# → Kafka UI:  http://localhost:30080/kafka-ui/
# → Keycloak:  http://localhost:30080/realms/master
```

### What runs where

The platform is split across two namespaces, matching production topology:

| Namespace | Services |
|-----------|----------|
| `ns-enterprise` | Kafka, Zookeeper, Schema Registry, Keycloak, MinIO, Kafka UI |
| `ns-controlplane` | Backend (Spring Boot), Frontend (Nginx), Nginx Flink proxy, PostgreSQL |
| `tenant-{slug}` | Flink jobs (provisioned dynamically on tenant creation) |

### Flink UI without port-forwarding

The Nginx Flink proxy routes browser requests directly to any running Flink job:

```
http://localhost:30080/flink/{tenant_slug}/{pipeline_id}/
```

No more `kubectl port-forward` per pipeline.

### Useful commands

```bash
make k8s-status                                     # pod health across all namespaces
make k8s-down                                       # stop (preserves tenant namespaces)
make flink-ui TENANT=acme PIPELINE=abc123           # print the Flink UI URL
```

### Docker Compose unchanged

The existing `make up` workflow is fully preserved. Switch freely between Docker Compose and Kubernetes modes depending on what you need to test.

## Requirements

- Docker Desktop with Kubernetes enabled
- `kubectl` and `helm` installed
- Run `make k8s-setup` once to install cert-manager and the Flink Operator
