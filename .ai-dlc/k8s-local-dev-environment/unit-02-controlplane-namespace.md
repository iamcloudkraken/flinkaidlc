---
status: in_progress
depends_on: [unit-01-enterprise-namespace]
branch: ai-dlc/k8s-local-dev-environment/02-controlplane-namespace
discipline: devops
workflow: ""
ticket: ""
---

# unit-02-controlplane-namespace

## Description

Create Kubernetes manifests for the `ns-controlplane` namespace containing all platform services: Postgres, the Spring Boot backend, the React frontend (with K8s-aware Nginx config), and a standalone Nginx Flink proxy. The frontend Nginx routes traffic to backend, Keycloak, Kafka UI, and the Flink proxy — all via in-cluster DNS. No port-forwarding needed for any service.

## Discipline

devops — Kubernetes YAML, Nginx configuration, RBAC.

## Domain Entities

- **ns-controlplane namespace**: Label `app.kubernetes.io/part-of: flink-platform-controlplane`. This label is referenced by TenantNamespaceProvisioner NetworkPolicy to allow ingress from the platform.
- **Postgres**: postgres:16-alpine, port 5432, database/user/password = flinkplatform
- **Backend**: Spring Boot JAR (locally built image `flinkaidlc-backend:latest`), port 8090, Spring profile `local-k8s`, connects to Postgres and Kafka via in-cluster DNS
- **Frontend**: Nginx serving React SPA (`flinkaidlc-frontend:latest`), port 80, routes /api/*, /realms/*, /kafka-ui/*, /flink/* via Nginx proxy_pass
- **Nginx Flink Proxy**: standalone Nginx Deployment for dynamic Flink REST routing, port 80, routes `/flink/{tenant_slug}/{pipeline_id}/` to `pipeline-{pipeline_id}-rest.tenant-{tenant_slug}.svc.cluster.local:8081`
- **Backend RBAC**: ClusterRole + ClusterRoleBinding granting the backend ServiceAccount permission to manage Namespaces, ServiceAccounts, Roles, RoleBindings, ResourceQuotas, NetworkPolicies across the cluster (needed by TenantNamespaceProvisioner)

## Data Sources

- `frontend/nginx.conf` (or `frontend/Dockerfile`) — existing Nginx config for Docker Compose, reference for route structure
- `docker-compose.yml` — Postgres env vars (POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD = flinkplatform)
- `src/main/resources/application-local.properties` — current local Spring profile, reference for what changes in local-k8s
- `dev/k8s/enterprise/` (from unit-01) — service names used in Nginx proxy_pass upstream URLs

## Technical Specification

Create directory `dev/k8s/controlplane/` containing:

```
dev/k8s/controlplane/
├── namespace.yaml              # Namespace: ns-controlplane
├── postgres.yaml               # Deployment + Service + PVC (emptyDir for dev)
├── backend.yaml                # Deployment + Service + ServiceAccount + ClusterRole + ClusterRoleBinding
├── frontend.yaml               # Deployment + Service + ConfigMap (Nginx config)
└── nginx-flink-proxy.yaml      # Deployment + Service + ConfigMap (Nginx config)
```

### namespace.yaml
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ns-controlplane
  labels:
    app.kubernetes.io/part-of: flink-platform-controlplane
```

### postgres.yaml
- Image: `postgres:16-alpine`, `imagePullPolicy: IfNotPresent`
- Env: `POSTGRES_DB=flinkplatform`, `POSTGRES_USER=flinkplatform`, `POSTGRES_PASSWORD=flinkplatform`
- Storage: emptyDir (dev — data lost on pod restart, acceptable for local dev)
- Service: ClusterIP, port 5432, named `postgresql` (matches DNS unit-03 expects: `postgresql.ns-controlplane.svc.cluster.local:5432`)
- Resource limits: 256Mi memory, 0.25 CPU

### backend.yaml

**Image**: `flinkaidlc-backend:latest` (built locally via `docker build`). `imagePullPolicy: Never` — Docker Desktop shares the daemon.

**Env vars** (override Spring Boot defaults):
- `SPRING_PROFILES_ACTIVE=local,local-k8s` — activates both base local config and K8s overrides
- `SPRING_DATASOURCE_URL=jdbc:postgresql://postgresql.ns-controlplane.svc.cluster.local:5432/flinkplatform`
- `SPRING_DATASOURCE_USERNAME=flinkplatform`
- `SPRING_DATASOURCE_PASSWORD=flinkplatform`

**ServiceAccount** named `backend` in ns-controlplane. The backend needs to call the K8s API to create/manage tenant namespaces.

**ClusterRole** `flink-platform-backend` with rules:
```yaml
rules:
- apiGroups: [""]
  resources: ["namespaces", "serviceaccounts", "resourcequotas"]
  verbs: ["get", "list", "create", "update", "patch", "delete"]
- apiGroups: ["rbac.authorization.k8s.io"]
  resources: ["roles", "rolebindings"]
  verbs: ["get", "list", "create", "update", "patch", "delete"]
- apiGroups: ["networking.k8s.io"]
  resources: ["networkpolicies"]
  verbs: ["get", "list", "create", "update", "patch", "delete"]
- apiGroups: ["flink.apache.org"]
  resources: ["flinkdeployments"]
  verbs: ["get", "list", "create", "update", "patch", "delete"]
```

**ClusterRoleBinding** binding `flink-platform-backend` ClusterRole to the `backend` ServiceAccount in ns-controlplane.

Service: ClusterIP, port 8090, named `backend`.

Resource limits: 512Mi memory, 0.5 CPU.

### frontend.yaml

**Image**: `flinkaidlc-frontend:latest`, `imagePullPolicy: Never`.

The frontend image builds the React app and serves it via Nginx. The Nginx config must be injected via a ConfigMap (overrides the image's default config) so routes can be changed without rebuilding the image.

**Nginx ConfigMap** (`frontend-nginx-config`):
```nginx
server {
    listen 80;

    # React SPA
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # Backend API
    location /api/ {
        proxy_pass http://backend.ns-controlplane.svc.cluster.local:8090/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # Keycloak
    location /realms/ {
        proxy_pass http://keycloak.ns-enterprise.svc.cluster.local:8080/realms/;
        proxy_set_header Host $host;
    }

    # Kafka UI (proxied through frontend Nginx)
    location /kafka-ui/ {
        proxy_pass http://kafka-ui.ns-enterprise.svc.cluster.local:8080/;
        proxy_set_header Host $host;
    }

    # Flink UI (proxied to Nginx Flink Proxy)
    location /flink/ {
        proxy_pass http://nginx-flink-proxy.ns-controlplane.svc.cluster.local:80/flink/;
        proxy_set_header Host $host;
    }
}
```

Mount this ConfigMap at `/etc/nginx/conf.d/default.conf` in the frontend container.

**Service**: NodePort, port 80 → nodePort 30080. This exposes the frontend at `http://localhost:30080` on Docker Desktop.

Resource limits: 128Mi memory, 0.1 CPU.

### nginx-flink-proxy.yaml

Standalone Nginx deployment for dynamic Flink REST API routing. Uses Nginx variables (`$1`, `$2`) and `resolver` directive to resolve per-pipeline DNS names dynamically without needing to reload config.

**Nginx ConfigMap** (`nginx-flink-proxy-config`):
```nginx
resolver kube-dns.kube-system.svc.cluster.local valid=10s;

server {
    listen 80;

    location ~ ^/flink/([^/]+)/([^/]+)/(.*)$ {
        set $tenant_slug $1;
        set $pipeline_id $2;
        set $rest_path $3;
        set $upstream "pipeline-${pipeline_id}-rest.tenant-${tenant_slug}.svc.cluster.local";

        proxy_pass http://$upstream:8081/$rest_path$is_args$args;
        proxy_set_header Host $host;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

**Key detail**: Using a variable in `proxy_pass` (`http://$upstream:8081/...`) forces Nginx to use the `resolver` for DNS lookup at request time, not at startup. This is critical — without it, Nginx fails to start if any Flink REST service doesn't exist yet.

**Service**: ClusterIP, port 80, named `nginx-flink-proxy`.

Resource limits: 64Mi memory, 0.1 CPU.

### Resource limits summary
- Postgres: 256Mi, 0.25 CPU
- Backend: 512Mi, 0.5 CPU
- Frontend: 128Mi, 0.1 CPU
- Nginx Flink Proxy: 64Mi, 0.1 CPU

## Success Criteria

- [ ] `kubectl apply -f dev/k8s/controlplane/` succeeds without errors
- [ ] All 4 Deployments reach `Running` state in ns-controlplane: `kubectl get pods -n ns-controlplane`
- [ ] Frontend accessible at `http://localhost:30080` (NodePort), React app loads
- [ ] `/api/v1/health` returns 200 via `http://localhost:30080/api/v1/health`
- [ ] `/kafka-ui/` proxies correctly: `http://localhost:30080/kafka-ui/` shows Kafka UI
- [ ] Backend ServiceAccount has ClusterRole with permission to create namespaces (verified by `kubectl auth can-i create namespaces --as=system:serviceaccount:ns-controlplane:backend`)

## Risks

- **Nginx variable-based proxy_pass requires resolver**: Without the `resolver` directive, Nginx fails to start or crashes on NXDOMAIN. The resolver must point to the cluster DNS (`kube-dns.kube-system.svc.cluster.local`). Mitigation: include resolver directive explicitly in the nginx-flink-proxy ConfigMap.
- **Backend image not built**: `flinkaidlc-backend:latest` must exist in Docker Desktop's daemon before applying manifests. If the image doesn't exist, the pod will go `ErrImageNeverPull`. Mitigation: `make k8s-up` (unit-04) must build the image before applying controlplane manifests.
- **Kafka UI path stripping**: When proxying `/kafka-ui/` to `kafka-ui.ns-enterprise.svc.cluster.local:8080/`, the trailing slash in `proxy_pass` strips the `/kafka-ui` prefix. Verify Kafka UI's internal paths resolve correctly. If Kafka UI uses absolute asset paths (e.g., `/static/...`), add a `sub_filter` or use Kafka UI's `SERVER_SERVLET_CONTEXT_PATH=/kafka-ui` env var instead.
- **RBAC scope**: The backend needs cluster-wide namespace creation rights. ClusterRole is appropriate here (not namespace-scoped Role). Ensure the binding uses ClusterRoleBinding (not RoleBinding) so the permission applies cluster-wide.

## Boundaries

This unit does NOT handle:
- ns-enterprise services (unit-01)
- Spring Boot `application-local-k8s.properties` or TenantNamespaceProvisioner Java code (unit-03)
- Makefile targets, setup scripts, or Flink Operator installation (unit-04)
- Building Docker images — assumed pre-built by `make k8s-up` (unit-04)
- TLS, Ingress controllers, or production-grade configuration

## Notes

- NodePort 30080 is used for the frontend. Ensure this port is not already in use.
- The `local-k8s` Spring profile (created in unit-03) is activated via `SPRING_PROFILES_ACTIVE=local,local-k8s` env var on the backend Deployment. The builder does not need to create the properties file — that's unit-03's job — but must set the env var correctly.
- Kafka UI may need `SERVER_SERVLET_CONTEXT_PATH=/kafka-ui` env var in its Deployment (unit-01) to handle path-based proxying correctly. If that env var wasn't set in unit-01, coordinate with unit-01 builder or add a note.
- The frontend Nginx config completely replaces the Docker Compose version. The Docker Compose frontend container used `keycloak:8080` (Docker Compose service hostname); K8s uses `keycloak.ns-enterprise.svc.cluster.local:8080`.
