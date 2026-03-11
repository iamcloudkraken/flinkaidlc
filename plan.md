# Tactical Plan: unit-02-controlplane-namespace

## Key Findings from Repo Exploration

- `frontend/nginx-local.conf` exists — Docker Compose Nginx config with /api/ and /realms/ routes. Base for K8s config (add /kafka-ui/ and /flink/).
- `application.yml` uses env vars: `DATABASE_URL`, `KUBERNETES_PLATFORM_NAMESPACE` (default: `flink-platform`), `OAUTH2_ISSUER_URI`
- `SPRING_DATASOURCE_URL` env var will override `spring.datasource.url` via Spring's relaxed binding (takes precedence over profile properties)
- Unit-01's kafka-ui Deployment does NOT set `SERVER_SERVLET_CONTEXT_PATH=/kafka-ui` — Kafka UI assets may 404 when proxied via `/kafka-ui/` prefix. Builder must also fix `dev/k8s/enterprise/06-kafka-ui.yaml`.
- nginx base image uses standard `include /etc/nginx/conf.d/*.conf` inside `http {}` — resolver directive before server{} is in http context (correct)

## File Structure

```
dev/k8s/controlplane/
├── namespace.yaml
├── postgres.yaml              (Deployment + Service)
├── backend.yaml               (ServiceAccount + ClusterRole + ClusterRoleBinding + Deployment + Service)
├── frontend.yaml              (ConfigMap + Deployment + Service)
└── nginx-flink-proxy.yaml     (ConfigMap + Deployment + Service)
```

## Common Patterns (ALL resources)

```yaml
metadata:
  namespace: ns-controlplane
  labels:
    app.kubernetes.io/part-of: flink-platform-controlplane
    app.kubernetes.io/name: <service>
# Deployments: selector.matchLabels AND pod template labels:
    app: <service>
spec:
  replicas: 1
```

## namespace.yaml

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ns-controlplane
  labels:
    app.kubernetes.io/part-of: flink-platform-controlplane
```

## postgres.yaml

```yaml
# Deployment
image: postgres:16-alpine
imagePullPolicy: IfNotPresent
env:
  POSTGRES_DB: flinkplatform
  POSTGRES_USER: flinkplatform
  POSTGRES_PASSWORD: flinkplatform
containerPort: 5432
volumeMounts: [name: data, mountPath: /var/lib/postgresql/data]
volumes: [name: data, emptyDir: {}]
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
readinessProbe: exec [pg_isready, -U, flinkplatform], initialDelaySeconds: 5, periodSeconds: 5

# Service
name: postgresql  # CRITICAL: must be 'postgresql' not 'postgres'
type: ClusterIP
port: 5432
```

## backend.yaml (5 resources separated by ---)

### ServiceAccount
```yaml
name: backend
namespace: ns-controlplane
```

### ClusterRole (cluster-scoped, no namespace)
```yaml
name: flink-platform-backend
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

### ClusterRoleBinding (cluster-scoped, no namespace)
```yaml
name: flink-platform-backend
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: flink-platform-backend
subjects:
  - kind: ServiceAccount
    name: backend
    namespace: ns-controlplane
```

### Deployment
```yaml
name: backend
namespace: ns-controlplane
spec.template.spec.serviceAccountName: backend
image: flinkaidlc-backend:latest
imagePullPolicy: Never
containerPort: 8090
env:
  SPRING_PROFILES_ACTIVE: "local,local-k8s"
  SERVER_PORT: "8090"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgresql.ns-controlplane.svc.cluster.local:5432/flinkplatform"
  SPRING_DATASOURCE_USERNAME: "flinkplatform"
  SPRING_DATASOURCE_PASSWORD: "flinkplatform"
  KUBERNETES_PLATFORM_NAMESPACE: "ns-controlplane"
resources: limits {memory: 512Mi, cpu: 500m}, requests {memory: 256Mi, cpu: 250m}
readinessProbe: httpGet /actuator/health port 8090, initialDelaySeconds: 30, periodSeconds: 10, failureThreshold: 6
```

### Service
```yaml
name: backend
type: ClusterIP
port: 8090
```

## frontend.yaml (3 resources)

### ConfigMap: frontend-nginx-config
Key: `default.conf`

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;

    location /api/ {
        proxy_pass http://backend.ns-controlplane.svc.cluster.local:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /realms/ {
        proxy_pass http://keycloak.ns-enterprise.svc.cluster.local:8080;
        proxy_set_header Host keycloak.ns-enterprise.svc.cluster.local:8080;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /kafka-ui/ {
        proxy_pass http://kafka-ui.ns-enterprise.svc.cluster.local:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /flink/ {
        proxy_pass http://nginx-flink-proxy.ns-controlplane.svc.cluster.local:80/flink/;
        proxy_set_header Host $host;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml;
}
```

### Deployment
```yaml
name: frontend
image: flinkaidlc-frontend:latest
imagePullPolicy: Never
containerPort: 80
volumeMounts: [name: nginx-config, mountPath: /etc/nginx/conf.d/default.conf, subPath: default.conf]
volumes: [name: nginx-config, configMap: {name: frontend-nginx-config}]
resources: limits {memory: 128Mi, cpu: 100m}, requests {memory: 64Mi, cpu: 50m}
livenessProbe: httpGet / port 80, initialDelaySeconds: 5, periodSeconds: 30
```

### Service
```yaml
name: frontend
type: NodePort
ports: [{port: 80, targetPort: 80, nodePort: 30080}]
```

## nginx-flink-proxy.yaml (3 resources)

### ConfigMap: nginx-flink-proxy-config
Key: `default.conf`

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

### Deployment
```yaml
name: nginx-flink-proxy
image: nginx:1.25-alpine
imagePullPolicy: IfNotPresent
containerPort: 80
volumeMounts: [name: nginx-config, mountPath: /etc/nginx/conf.d/default.conf, subPath: default.conf]
volumes: [name: nginx-config, configMap: {name: nginx-flink-proxy-config}]
resources: limits {memory: 64Mi, cpu: 100m}, requests {memory: 32Mi, cpu: 50m}
livenessProbe: tcpSocket {port: 80}, initialDelaySeconds: 5, periodSeconds: 30
```

### Service
```yaml
name: nginx-flink-proxy
type: ClusterIP
port: 80
```

## Also fix dev/k8s/enterprise/06-kafka-ui.yaml

Add env vars to the kafka-ui Deployment so /kafka-ui/ proxy works correctly:
- `SERVER_SERVLET_CONTEXT_PATH=/kafka-ui`
- `DYNAMIC_CONFIG_ENABLED=true`

## Commit

```bash
git add dev/k8s/controlplane/ dev/k8s/enterprise/06-kafka-ui.yaml
git commit -m "build(unit-02-controlplane-namespace): create controlplane namespace K8s manifests"
```
