---
intent: k8s-local-dev-environment
created: 2026-03-10
status: active
---

# Discovery Log: Kubernetes Local Dev Environment

Elaboration findings persisted during Phase 2.5 domain discovery.
Builders: read section headers for an overview, then dive into specific sections as needed.

## Current Docker Compose Services

| Service | Image | Container Port | Host Port | Internal URL |
|---------|-------|-----------------|-----------|--------------|
| postgres | postgres:16-alpine | 5432 | 5433 | postgres:5432 |
| keycloak | quay.io/keycloak/keycloak:24.0 | 8080 | 8180 | keycloak:8080 |
| zookeeper | confluentinc/cp-zookeeper:7.6.0 | 2181 | 2182 | zookeeper:2181 |
| kafka | confluentinc/cp-kafka:7.6.0 | 9092/29092 | 9192 | kafka:29092 (internal) |
| schema-registry | confluentinc/cp-schema-registry:7.6.0 | 8081 | 8182 | schema-registry:8081 |
| backend | Spring Boot JAR | 8090 | 8191 | backend:8090 |
| frontend | Node.js + Nginx | 80 | 3100 | — |

Key env vars: PostgreSQL DB/USER/PASSWORD=flinkplatform; Kafka internal=kafka:29092; Keycloak admin=admin/admin; Backend K8S_PROVISIONER_ENABLED=false

## Existing K8s Setup (kind)

Script: dev/setup-kind.sh — cluster: flink-local
- cert-manager v1.14.4
- Flink Kubernetes Operator v1.8.0 (namespace: flink-operator, webhook enabled)
- MinIO v14.6.0 Bitnami (namespace: minio, credentials: minioadmin/minioadmin, bucket: flink-local, NodePort: API 30900→9000, Console 30090→9090)

kind-config.yaml: single control-plane, port mappings 30081→8081, 30090→9090

## Backend Configuration

Spring Boot 3.3.10, Java 21, Fabric8 Kubernetes Client 6.13.1

application.yml base props:
- kubernetes.namespace-prefix: "tenant-"
- kubernetes.platform-namespace: "flink-platform"
- flink.sql-runner.image: "flink-sql-runner:latest"
- flink.pods-per-pipeline: 4
- jwt.tenant-id-claim: "tenant_id"

application-local.properties (Docker Compose mode):
- spring.datasource.url=jdbc:postgresql://localhost:5432/flinkplatform (username/password=flinkplatform)
- k8s.provisioner.enabled=false
- spring.security.oauth2.resourceserver.jwt.issuer-uri= (blank → mock JWT, any Bearer token accepted)
- schema.registry.url=http://localhost:8082

New application-local-k8s.properties (K8s mode) needed:
- spring.datasource.url=jdbc:postgresql://postgresql.ns_enterprise.svc.cluster.local:5432/flinkplatform
- k8s.provisioner.enabled=true
- spring.security.oauth2.resourceserver.jwt.issuer-uri= (keep blank → mock JWT)
- schema.registry.url=http://schema-registry.ns_enterprise.svc.cluster.local:8081
- kubernetes.platform-namespace=ns_controlplane

## TenantNamespaceProvisioner

Class: com.flinkaidlc.platform.k8s.TenantNamespaceProvisioner
- @ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "true", matchIfMissing = true)
- Namespace pattern: {namespacePrefix}{tenant_slug} → "tenant-{slug}" (matches kubernetes.namespace-prefix)

Resources created per tenant: Namespace, ServiceAccount "flink", Role "flink", RoleBinding, ResourceQuota (pods, cpu, memory), NetworkPolicy (allows ingress from same namespace + platform namespace).

CRITICAL: NetworkPolicy currently allows ingress from platform namespace labeled "flink-platform".
In K8s local dev, this must be updated to "ns_controlplane".

NoOpTenantNamespaceProvisioner: activated when k8s.provisioner.enabled=false.

## KubernetesClient Usage

KubernetesConfig bean: new KubernetesClientBuilder().build() — auto-detects ~/.kube/config or in-cluster SA.
Usage: TenantNamespaceProvisioner, FlinkOrchestrationServiceImpl, FlinkDeploymentBuilder, FlinkDeploymentStatusSyncer.
FlinkDeployment CRD: flink.apache.org/v1beta1.
NoOpFlinkOrchestrationService: activated when k8s.provisioner.enabled=false.

## Makefile Targets

Current: build, up, infra, down, clean, logs, flink-ui
flink-ui: kubectl port-forward svc/{pipeline}-rest 8081:8081 -n tenant-{slug}

New targets needed: k8s-up, k8s-down, k8s-status
Updated flink-ui: show Nginx URL http://localhost/flink/{tenant_slug}/{pipeline_name}/ instead of port-forwarding

## Frontend Config

Docker Compose port: 3100. Nginx routes: /api/* → backend:8090, /realms/* → keycloak:8080.
React 18.3.1, Vite, axios, Tailwind CSS, react-router-dom.

In K8s, frontend Nginx config must route:
- /api/* → backend.ns_controlplane.svc.cluster.local:8090
- /realms/* → keycloak.ns_enterprise.svc.cluster.local:8080
- /flink/* → nginx-flink-proxy.ns_controlplane.svc.cluster.local:80

## Key Decisions for Builder

1. Namespace names: ns_enterprise (infra), ns_controlplane (platform), tenant-{slug} (Flink jobs)
   - kubernetes.platform-namespace → "ns_controlplane"
   - kubernetes.namespace-prefix → "tenant-" (unchanged)

2. Flink REST service: Flink Operator names it "{flinkdeployment-name}-rest"
   FlinkDeploymentBuilder sets resource name = "pipeline-{pipeline_id}"
   → REST service = pipeline-{pipeline_id}-rest in namespace tenant-{slug}

3. Nginx proxy routing pattern:
   - Request: /flink/{tenant_slug}/{pipeline_id}/
   - Target: http://pipeline-{pipeline_id}-rest.tenant-{tenant_slug}.svc.cluster.local:8081/
   - Nginx must strip prefix before proxying. Use nginx rewrite:
     rewrite ^/flink/[^/]+/([^/]+)/(.*)$ /$2 break; (strips tenant and pipeline prefix)

4. Nginx architecture: standalone Nginx Deployment + ConfigMap in ns_controlplane.
   Dynamic routes: Nginx ConfigMap must be updated when pipelines are deployed.
   Simple MVP: use a catch-all dynamic proxy pattern with variables.

5. Docker images in Docker Desktop: imagePullPolicy: Never, built locally with docker build.
   No registry needed — Docker Desktop shares the local Docker daemon.

6. MinIO in K8s: Bitnami Helm chart in ns_enterprise (minioadmin/minioadmin, bucket: flink-local)
   minio.ns_enterprise.svc.cluster.local:9000

7. Keycloak JWT: Keep mock JWT decoder in local-k8s profile (blank issuer-uri) — avoids
   issuer URI mismatch between localhost token issuer and in-cluster URL.

8. LocalDataSeeder kafka bootstrap in K8s profile: kafka.ns_enterprise.svc.cluster.local:29092
   schema-registry: schema-registry.ns_enterprise.svc.cluster.local:8081
