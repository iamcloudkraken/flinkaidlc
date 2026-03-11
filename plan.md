# Tactical Plan: unit-01-enterprise-namespace

## Key Findings from Repo Exploration

- `docker-compose.yml` at root — source of truth for image versions and env vars
- `docker/keycloak/realm-export.json` EXISTS — must be embedded as ConfigMap and mounted into Keycloak
- `dev/` has no `k8s/` subdirectory yet — create from scratch
- MinIO credentials from setup-kind.sh: minioadmin / minioadmin, bucket: flink-local
- Kafka internal listener name in docker-compose: `PLAINTEXT_INTERNAL` (use this, not `PLAINTEXT_HOST`)
- Kafka UI not in docker-compose — use `provectuslabs/kafka-ui:latest`

## File Creation Order

```
dev/k8s/enterprise/
├── 00-namespace.yaml
├── 01-zookeeper.yaml
├── 02-kafka.yaml
├── 03-schema-registry.yaml
├── 04-keycloak.yaml           (includes ConfigMap for realm-export.json)
├── 05-minio.yaml
├── 06-kafka-ui.yaml
└── 07-iceberg-rest.yaml       (initContainer waits for MinIO health)
```

## Common Patterns (apply to ALL resources)

```yaml
# All resources:
metadata:
  namespace: ns-enterprise
  labels:
    app.kubernetes.io/part-of: flink-platform-enterprise
    app.kubernetes.io/name: <service-name>

# Deployments: spec.selector.matchLabels AND spec.template.metadata.labels:
    app: <service-name>

# Services: spec.selector:
    app: <service-name>

# All Deployments:
spec:
  replicas: 1
```

## 00-namespace.yaml

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ns-enterprise
  labels:
    app.kubernetes.io/part-of: flink-platform-enterprise
```

## 01-zookeeper.yaml

```yaml
image: confluentinc/cp-zookeeper:7.6.0
imagePullPolicy: IfNotPresent
env:
  ZOOKEEPER_CLIENT_PORT: "2181"
  ZOOKEEPER_TICK_TIME: "2000"
containerPort: 2181
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
Service: name=zookeeper, ClusterIP, port 2181
```

## 02-kafka.yaml

```yaml
image: confluentinc/cp-kafka:7.6.0
imagePullPolicy: IfNotPresent
env:
  KAFKA_BROKER_ID: "1"
  KAFKA_ZOOKEEPER_CONNECT: "zookeeper.ns-enterprise.svc.cluster.local:2181"
  KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT_INTERNAL://kafka.ns-enterprise.svc.cluster.local:29092,PLAINTEXT://localhost:9092"
  KAFKA_LISTENERS: "PLAINTEXT_INTERNAL://0.0.0.0:29092,PLAINTEXT://0.0.0.0:9092"
  KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "PLAINTEXT_INTERNAL:PLAINTEXT,PLAINTEXT:PLAINTEXT"
  KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT_INTERNAL"
  KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
  KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
containerPorts: 29092, 9092
resources: limits {memory: 512Mi, cpu: 500m}, requests {memory: 256Mi, cpu: 250m}
Service: name=kafka, ClusterIP, ports 29092 + 9092
```

## 03-schema-registry.yaml

```yaml
image: confluentinc/cp-schema-registry:7.6.0
imagePullPolicy: IfNotPresent
env:
  SCHEMA_REGISTRY_HOST_NAME: "schema-registry"
  SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: "kafka.ns-enterprise.svc.cluster.local:29092"
  SCHEMA_REGISTRY_LISTENERS: "http://0.0.0.0:8081"
containerPort: 8081
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
Service: name=schema-registry, ClusterIP, port 8081
```

## 04-keycloak.yaml

Two resources in one file (ConfigMap + Deployment + Service):

**ConfigMap**: `keycloak-realm-import` in ns-enterprise
- key: `realm-export.json`
- value: full contents of `docker/keycloak/realm-export.json` (read the file and embed it)

**Deployment**:
```yaml
image: quay.io/keycloak/keycloak:24.0
imagePullPolicy: IfNotPresent
command: ["start-dev", "--import-realm"]
env:
  KEYCLOAK_ADMIN: "admin"
  KEYCLOAK_ADMIN_PASSWORD: "admin"
containerPort: 8080
volumeMounts:
  - name: realm-import
    mountPath: /opt/keycloak/data/import
volumes:
  - name: realm-import
    configMap:
      name: keycloak-realm-import
startupProbe: httpGet /realms/master, failureThreshold: 30, periodSeconds: 10
resources: limits {memory: 512Mi, cpu: 500m}, requests {memory: 256Mi, cpu: 250m}
```

**Service**: name=keycloak, ClusterIP, port 8080

## 05-minio.yaml

```yaml
image: bitnami/minio:latest
imagePullPolicy: IfNotPresent
env:
  MINIO_ROOT_USER: "minioadmin"
  MINIO_ROOT_PASSWORD: "minioadmin"
  MINIO_DEFAULT_BUCKETS: "flink-local"
containerPorts: 9000 (API), 9001 (Console)
volumeMounts:
  - name: data, mountPath: /bitnami/minio/data
volumes:
  - name: data, emptyDir: {}
livenessProbe: httpGet /minio/health/live port 9000
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
Service: name=minio, ClusterIP, port 9000 (api) + 9001 (console)
```

## 06-kafka-ui.yaml

```yaml
image: provectuslabs/kafka-ui:latest
imagePullPolicy: IfNotPresent
env:
  KAFKA_CLUSTERS_0_NAME: "local"
  KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: "kafka.ns-enterprise.svc.cluster.local:29092"
  KAFKA_CLUSTERS_0_SCHEMAREGISTRY: "http://schema-registry.ns-enterprise.svc.cluster.local:8081"
containerPort: 8080
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
Service: name=kafka-ui, ClusterIP, port 8080
```

## 07-iceberg-rest.yaml

```yaml
initContainers:
  - name: wait-for-minio
    image: curlimages/curl:latest
    imagePullPolicy: IfNotPresent
    command: [sh, -c, "until curl -sf http://minio.ns-enterprise.svc.cluster.local:9000/minio/health/live; do echo 'Waiting for MinIO...'; sleep 3; done; echo 'MinIO ready.'"]

image: tabulario/iceberg-rest:latest
imagePullPolicy: IfNotPresent
env:
  CATALOG_WAREHOUSE: "s3://flink-local/iceberg"
  CATALOG_IO__IMPL: "org.apache.iceberg.aws.s3.S3FileIO"
  CATALOG_S3_ENDPOINT: "http://minio.ns-enterprise.svc.cluster.local:9000"
  CATALOG_S3_ACCESS__KEY__ID: "minioadmin"
  CATALOG_S3_SECRET__ACCESS__KEY: "minioadmin"
  CATALOG_S3_PATH__STYLE__ACCESS: "true"
containerPort: 8181
resources: limits {memory: 256Mi, cpu: 250m}, requests {memory: 128Mi, cpu: 100m}
Service: name=iceberg-rest, ClusterIP, port 8181
```

## Critical Notes

1. **Kafka listener**: Use `PLAINTEXT_INTERNAL` (not `PLAINTEXT_HOST`) — matches docker-compose
2. **Keycloak realm**: Read `docker/keycloak/realm-export.json` and embed in ConfigMap — do NOT skip this
3. **MinIO env var**: `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` (NOT `MINIO_ACCESS_KEY`)
4. **Iceberg double underscores**: `CATALOG_S3_ACCESS__KEY__ID` — double underscore is required
5. **Iceberg warehouse**: Use `s3://` (not `s3a://`) for tabulario/iceberg-rest image
6. **No depends_on in K8s**: Services will self-heal via CrashLoopBackOff. Add initContainers only for Iceberg→MinIO dependency

## Verification Commands

```bash
kubectl apply -f dev/k8s/enterprise/
kubectl get pods -n ns-enterprise -w
kubectl exec -n ns-enterprise deploy/schema-registry -- curl -sf http://localhost:8081/subjects
kubectl exec -n ns-enterprise deploy/minio -- curl -sf http://localhost:9000/minio/health/live
kubectl exec -n ns-enterprise deploy/iceberg-rest -- curl -sf http://localhost:8181/v1/config
```
