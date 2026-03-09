---
intent: flink-sql-pipeline-platform
created: 2026-03-08
status: active
---

# Discovery Log: Flink SQL Pipeline Platform

Elaboration findings persisted during Phase 2.5 domain discovery.
Builders: read section headers for an overview, then dive into specific sections as needed.

---

## 1. System Overview

The platform is a **multi-tenant Flink SQL pipeline management service**. Clients POST a pipeline spec (SQL + Kafka source/sink config + job settings) to a Spring Boot REST API. The API stores the spec, generates a `statements.sql` file, builds a Docker image containing the SQL runner JAR and script, and applies a `FlinkDeployment` custom resource to a per-tenant Kubernetes namespace. The Flink Kubernetes Operator reconciles this CRD into a running Flink application-mode job.

### High-Level Flow

```
Client HTTP Request
     │
     ▼
Spring Boot REST API
  ├── Validates SQL + Kafka config
  ├── Persists PipelineSpec → PostgreSQL
  ├── Generates statements.sql
  ├── Builds/pushes Docker image (SQL runner + statements.sql)
  └── Applies FlinkDeployment CRD → Kubernetes (tenant namespace)
             │
             ▼
    Flink Kubernetes Operator
    (watches tenant namespaces)
             │
             ▼
    Flink Application-Mode Job
    reads Kafka topic → SQL transform → writes Kafka topic
```

---

## 2. Apache Flink SQL — Capabilities and Syntax

### 2.1 Kafka Source Table DDL

```sql
CREATE TABLE orders_source (
  order_id    STRING,
  customer_id STRING,
  amount      DECIMAL(10, 2),
  event_time  TIMESTAMP(3),
  WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
) WITH (
  'connector'                    = 'kafka',
  'topic'                        = 'orders-input',
  'properties.bootstrap.servers' = 'kafka:9092',
  'properties.group.id'          = 'pipeline-<pipeline-id>',
  'scan.startup.mode'            = 'earliest-offset',
  'format'                       = 'json',
  'json.ignore-parse-errors'     = 'true'
);
```

Key `scan.startup.mode` values:
- `group-offsets` (default) — resume from last committed offset for the consumer group
- `earliest-offset` — read from beginning of topic
- `latest-offset` — read only new messages
- `timestamp` — requires `scan.startup.timestamp-millis`
- `specific-offsets` — requires `scan.startup.specific-offsets` (e.g., `partition:0,offset:42;partition:1,offset:300`)

### 2.2 Kafka Sink Table DDL

```sql
CREATE TABLE order_counts_sink (
  customer_id   STRING,
  order_count   BIGINT,
  window_start  TIMESTAMP(3),
  window_end    TIMESTAMP(3)
) WITH (
  'connector'                    = 'kafka',
  'topic'                        = 'order-counts-output',
  'properties.bootstrap.servers' = 'kafka:9092',
  'format'                       = 'json',
  'sink.partitioner'             = 'fixed',
  'sink.delivery-guarantee'      = 'exactly-once'
);
```

Sink partitioner options:
- `default` — Kafka default (sticky for null-key, murmur2 hash for keyed)
- `fixed` — same Flink partition → same Kafka partition (lower network overhead)
- `round-robin` — round-robin distribution

Delivery guarantees:
- `at-least-once` (default when checkpointing enabled)
- `exactly-once` — requires Flink checkpointing enabled AND Kafka transactions configured

### 2.3 Upsert-Kafka Connector

Used for changelog streams and compacted topics. **Requires a PRIMARY KEY**.

```sql
CREATE TABLE customer_order_stats (
  customer_id  STRING,
  order_count  BIGINT,
  total_spend  DECIMAL(12, 2),
  PRIMARY KEY (customer_id) NOT ENFORCED
) WITH (
  'connector'                    = 'upsert-kafka',
  'topic'                        = 'customer-stats',
  'properties.bootstrap.servers' = 'kafka:9092',
  'key.format'                   = 'json',
  'value.format'                 = 'json'
);
```

- NULL value rows emit a Kafka tombstone (DELETE signal for log compaction)
- Source topic must be compacted and all records with the same key in the same partition
- Produces a changelog stream: INSERT/UPDATE per key

### 2.4 Metadata Columns (Source)

```sql
CREATE TABLE enriched_source (
  `topic`      STRING METADATA VIRTUAL,
  `partition`  INT    METADATA VIRTUAL,
  `offset`     BIGINT METADATA VIRTUAL,
  `event_time` TIMESTAMP_LTZ(3) METADATA FROM 'timestamp' VIRTUAL,
  order_id     STRING,
  amount       DECIMAL(10, 2)
) WITH (
  'connector' = 'kafka',
  ...
);
```

### 2.5 Supported Formats

| Format | connector option value | Notes |
|--------|------------------------|-------|
| JSON | `'format' = 'json'` | Common, schema inferred from DDL |
| Avro | `'format' = 'avro'` | Requires Avro schema in DDL |
| Confluent Avro (Schema Registry) | `'value.format' = 'avro-confluent'` | Needs `value.avro-confluent.url` pointing to schema registry |
| CSV | `'format' = 'csv'` | Simple, text-based |
| Raw | `'format' = 'raw'` | Single column raw bytes |

For Avro with Confluent Schema Registry:
```sql
WITH (
  'connector'                = 'kafka',
  'value.format'             = 'avro-confluent',
  'value.avro-confluent.url' = 'http://schema-registry:8081'
)
```

### 2.6 Window Aggregations

Flink SQL uses **Windowing Table-Valued Functions (TVF)**. Four types are supported:

#### Tumbling Window (non-overlapping, fixed size)
```sql
SELECT
  window_start,
  window_end,
  customer_id,
  COUNT(*) AS order_count
FROM TUMBLE(
  TABLE orders_source,
  DESCRIPTOR(event_time),
  INTERVAL '1' HOUR
)
GROUP BY window_start, window_end, customer_id;
```

#### Sliding / Hop Window (overlapping)
```sql
SELECT
  window_start,
  window_end,
  SUM(amount) AS revenue
FROM HOP(
  TABLE orders_source,
  DESCRIPTOR(event_time),
  INTERVAL '10' MINUTES,   -- slide interval
  INTERVAL '1' HOUR        -- window size
)
GROUP BY window_start, window_end;
```

#### Session Window
```sql
SELECT
  window_start,
  window_end,
  customer_id,
  COUNT(*) AS session_events
FROM SESSION(
  TABLE orders_source PARTITION BY customer_id,
  DESCRIPTOR(event_time),
  INTERVAL '30' MINUTES    -- session gap
)
GROUP BY window_start, window_end, customer_id;
```

#### Cumulate Window
```sql
SELECT
  window_start,
  window_end,
  SUM(amount) AS cumulative_revenue
FROM CUMULATE(
  TABLE orders_source,
  DESCRIPTOR(event_time),
  INTERVAL '1' HOUR,   -- step
  INTERVAL '1' DAY     -- max window size
)
GROUP BY window_start, window_end;
```

All windowing TVFs return additional columns: `window_start`, `window_end`, `window_time`.

### 2.7 Key SQL Operations

- **Standard aggregations**: COUNT, SUM, AVG, MIN, MAX, FIRST_VALUE, LAST_VALUE
- **Joins**: Regular join, interval join (time-bounded), temporal join (lookup join with latest version)
- **Pattern recognition**: MATCH_RECOGNIZE for CEP
- **Deduplication**: ROW_NUMBER() OVER (PARTITION BY key ORDER BY event_time) with WHERE rn = 1
- **INSERT INTO**: Continuous write to sink table

```sql
INSERT INTO order_counts_sink
SELECT
  window_start, window_end, customer_id, COUNT(*) AS order_count
FROM TUMBLE(TABLE orders_source, DESCRIPTOR(event_time), INTERVAL '1' HOUR)
GROUP BY window_start, window_end, customer_id;
```

### 2.8 statements.sql Structure for SQL Runner

The `statements.sql` file executed by the SQL runner JAR can contain multiple statements in sequence:

```sql
-- 1. Create source table
CREATE TABLE kafka_source (...) WITH (...);

-- 2. Create sink table
CREATE TABLE kafka_sink (...) WITH (...);

-- 3. Optional: create intermediate views
CREATE TEMPORARY VIEW enriched AS
  SELECT *, UPPER(customer_id) AS customer_key FROM kafka_source;

-- 4. Execute the pipeline (blocking INSERT INTO)
INSERT INTO kafka_sink
SELECT window_start, window_end, customer_key, COUNT(*) AS cnt
FROM TUMBLE(TABLE enriched, DESCRIPTOR(event_time), INTERVAL '5' MINUTES)
GROUP BY window_start, window_end, customer_key;
```

---

## 3. Flink Kubernetes Operator

### 3.1 CRD Overview

The Flink Kubernetes Operator extends the Kubernetes API with two primary CRDs:

| CRD | Kind | Use Case |
|-----|------|----------|
| `FlinkDeployment` | Application cluster or Session cluster | One job per deployment (Application Mode) or shared cluster |
| `FlinkSessionJob` | Job on a Session cluster | Multiple jobs sharing one cluster |

**For this platform**: Application Mode with one `FlinkDeployment` per pipeline. This provides full isolation between pipelines per tenant.

API version: `flink.apache.org/v1beta1`

### 3.2 FlinkDeployment Spec — Complete Reference

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: pipeline-<pipeline-id>
  namespace: tenant-<tenant-id>
spec:
  image: <registry>/<tenant>/<pipeline-image>:<tag>
  imagePullPolicy: Always
  flinkVersion: v1_20          # v1_17, v1_18, v1_19, v1_20, v2_0 etc.
  serviceAccount: flink         # Must exist in the namespace
  mode: native                  # native (default) or standalone

  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "2"
    execution.checkpointing.interval: "10000"          # ms
    execution.checkpointing.mode: EXACTLY_ONCE
    state.backend.type: rocksdb
    state.checkpoints.dir: s3://flink-state/<tenant>/<pipeline-id>/checkpoints
    state.savepoints.dir: s3://flink-state/<tenant>/<pipeline-id>/savepoints
    execution.checkpointing.num-retained: "3"
    restart-strategy.type: failure-rate
    restart-strategy.failure-rate.max-failures-per-interval: "10"
    restart-strategy.failure-rate.failure-rate-interval: "10 min"
    restart-strategy.failure-rate.delay: "30 s"
    table.exec.source.idle-timeout: "30 s"

  jobManager:
    replicas: 1                 # 2 for HA
    resource:
      memory: "2048m"
      cpu: 0.5

  taskManager:
    resource:
      memory: "2048m"
      cpu: 1.0

  job:
    jarURI: local:///opt/flink/usrlib/sql-runner.jar
    args: ["/opt/flink/usrlib/sql-scripts/statements.sql"]
    parallelism: 2              # Required for application mode
    state: running              # running | suspended
    upgradeMode: savepoint      # stateless | last-state | savepoint
    allowNonRestoredState: false
    initialSavepointPath: ""    # Set on restore from specific savepoint
```

### 3.3 FlinkDeployment Observed Status

After applying the CRD, the operator populates `status`:
- `status.jobStatus.state`: RUNNING, FAILED, FINISHED, SUSPENDED
- `status.lifecycleState`: CREATED, DEPLOYED, STABLE, UPGRADING, SUSPENDED, FAILED
- `status.error`: Error message if job failed
- `status.jobStatus.savepointInfo.lastSavepoint`: Last savepoint location

### 3.4 SQL Runner Approach

Since Flink cannot natively submit a `.sql` file as a job, the standard approach is:

1. **SQL Runner JAR**: A thin Java application (available in the [flink-sql-runner-example](https://github.com/apache/flink-kubernetes-operator/tree/main/examples/flink-sql-runner-example)) that:
   - Accepts a path to `statements.sql` as argument
   - Creates a `StreamTableEnvironment`
   - Iterates through SQL statements and calls `tableEnv.executeSql(statement)`
   - The final `INSERT INTO` triggers the streaming job

2. **Custom Docker Image per Pipeline**:
```dockerfile
FROM flink:1.20
COPY sql-runner.jar /opt/flink/usrlib/sql-runner.jar
COPY statements.sql /opt/flink/usrlib/sql-scripts/statements.sql
```

3. **Alternative — Dynamic SQL via ConfigMap**: Mount `statements.sql` as a ConfigMap volume instead of baking it into the image. This avoids a Docker build per pipeline change. The `podTemplate` field can mount a ConfigMap.

**Platform Design Decision**: ConfigMap mounting is preferred over image-per-pipeline for the REST API use case because:
- No Docker build/push cycle needed on pipeline create/update
- Faster pipeline deployment
- ConfigMap contents can be updated and job restarted

```yaml
spec:
  job:
    jarURI: local:///opt/flink/usrlib/sql-runner.jar
    args: ["/opt/flink/usrlib/sql-scripts/statements.sql"]
  podTemplate:
    spec:
      containers:
        - name: flink-main-container
          volumeMounts:
            - name: sql-script
              mountPath: /opt/flink/usrlib/sql-scripts
      volumes:
        - name: sql-script
          configMap:
            name: pipeline-<pipeline-id>-sql
```

### 3.5 Job Lifecycle via FlinkDeployment

| Operation | Mechanism |
|-----------|-----------|
| Create pipeline | Apply `FlinkDeployment` with `state: running` |
| Pause pipeline | Patch `spec.job.state: suspended` → operator triggers savepoint then stops |
| Resume pipeline | Patch `spec.job.state: running` → operator restores from last savepoint |
| Update SQL | Update ConfigMap + set new `restartNonce` value → triggers rolling restart |
| Delete pipeline | Delete `FlinkDeployment` resource (and ConfigMap) |
| Manual savepoint | Patch `spec.job.savepointTriggerNonce` to a new value |
| Scale parallelism | Patch `spec.job.parallelism` → triggers upgrade (savepoint + redeploy) |

Upgrade modes:
- `savepoint` — safest, creates savepoint before stopping; requires configured savepoint dir
- `last-state` — uses HA checkpoint metadata; faster but less safe
- `stateless` — cancel and restart from empty state; only for stateless pipelines

### 3.6 Supported FlinkVersions

| Field Value | Flink Release |
|-------------|---------------|
| `v1_17` | Apache Flink 1.17 |
| `v1_18` | Apache Flink 1.18 |
| `v1_19` | Apache Flink 1.19 |
| `v1_20` | Apache Flink 1.20 |
| `v2_0` | Apache Flink 2.0 |

Recommend targeting `v1_20` (latest stable) or `v2_0` if available.

---

## 4. Multi-Tenant Kubernetes Architecture

### 4.1 Namespace Isolation Strategy

Each tenant receives a dedicated Kubernetes namespace: `tenant-<tenant-slug>`.

Isolation boundaries enforced per namespace:
- **Compute**: Flink jobs in one namespace cannot affect another
- **RBAC**: Tenant service accounts scoped to their namespace only
- **Network Policies**: Block inter-namespace traffic
- **Resource Quotas**: Cap CPU, memory, pod count per tenant

### 4.2 Flink Kubernetes Operator — Multi-Namespace Setup

**Option A: Cluster-Scoped Operator (recommended for this platform)**

The operator is installed once and watches all namespaces (or a dynamically growing list via `watchNamespaces`). Each tenant namespace must have:
- `ServiceAccount` named `flink`
- `Role` named `flink` with required permissions
- `RoleBinding` binding the role to the service account

Required Role permissions (per namespace):
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: flink
  namespace: tenant-<tenant-id>
rules:
  - apiGroups: [""]
    resources: ["pods", "configmaps"]
    verbs: ["*"]
  - apiGroups: ["apps"]
    resources: ["deployments", "deployments/finalizers"]
    verbs: ["*"]
```

**Option B: watchNamespaces Helm configuration**

When installing the operator with Helm, namespaces can be pre-declared:
```bash
helm install flink-kubernetes-operator \
  --set watchNamespaces="{tenant-a,tenant-b,tenant-c}"
```
The operator automatically creates namespace-scoped Roles in each watched namespace. However, dynamic tenant onboarding requires operator Helm upgrade or a cluster-scoped operator with manual namespace setup.

**Recommended approach for dynamic tenant creation**: Use a cluster-scoped operator. The Spring Boot API creates the tenant namespace and creates the flink ServiceAccount, Role, and RoleBinding programmatically via the Kubernetes client.

### 4.3 Network Policies for Tenant Isolation

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-cross-tenant
  namespace: tenant-<tenant-id>
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: tenant-<tenant-id>
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: tenant-<tenant-id>
    - to: {}         # Allow egress to Kafka, schema registry, S3 etc.
      ports:
        - port: 9092  # Kafka
        - port: 443   # HTTPS
```

### 4.4 Resource Quotas Per Tenant

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: tenant-quota
  namespace: tenant-<tenant-id>
spec:
  hard:
    requests.cpu: "8"
    requests.memory: 16Gi
    limits.cpu: "16"
    limits.memory: 32Gi
    count/pods: "50"
    count/flinkdeployments.flink.apache.org: "10"
```

### 4.5 Tenant Onboarding Flow (API-driven)

When the REST API creates a new tenant, it must:
1. Create Kubernetes namespace `tenant-<tenant-id>`
2. Apply labels (`app.kubernetes.io/tenant: <tenant-id>`)
3. Create `ServiceAccount` named `flink` in the namespace
4. Create `Role` named `flink` with TaskManager management permissions
5. Create `RoleBinding` binding the role to the service account
6. Apply `ResourceQuota` for resource limits
7. Apply `NetworkPolicy` for isolation
8. Persist tenant record to PostgreSQL

---

## 5. Spring Boot + Kubernetes Client Integration

### 5.1 Recommended Libraries

| Library | Purpose | Maven Group |
|---------|---------|-------------|
| `fabric8 kubernetes-client` | Primary K8s client (typed + generic API) | `io.fabric8:kubernetes-client` |
| `spring-boot-starter-web` | REST API | `org.springframework.boot` |
| `spring-boot-starter-data-jpa` | Pipeline persistence | `org.springframework.boot` |
| `postgresql` | Database driver | `org.postgresql:postgresql` |
| `jackson-dataformat-yaml` | YAML generation for FlinkDeployment | `com.fasterxml.jackson.dataformat` |

### 5.2 Fabric8 Client — Creating Namespaces

```java
@Service
public class KubernetesProvisioningService {

    private final KubernetesClient kubernetesClient;

    public void createTenantNamespace(String tenantId) {
        String namespace = "tenant-" + tenantId;

        Namespace ns = new NamespaceBuilder()
            .withNewMetadata()
                .withName(namespace)
                .addToLabels("app.kubernetes.io/managed-by", "flink-pipeline-platform")
                .addToLabels("app.kubernetes.io/tenant", tenantId)
            .endMetadata()
            .build();

        kubernetesClient.namespaces().resource(ns).createOrReplace();
    }
}
```

### 5.3 Fabric8 Client — Creating RBAC Resources

```java
public void createFlinkServiceAccount(String tenantId) {
    String namespace = "tenant-" + tenantId;

    // ServiceAccount
    ServiceAccount sa = new ServiceAccountBuilder()
        .withNewMetadata()
            .withName("flink")
            .withNamespace(namespace)
        .endMetadata()
        .build();
    kubernetesClient.serviceAccounts().inNamespace(namespace).resource(sa).createOrReplace();

    // Role
    Role role = new RoleBuilder()
        .withNewMetadata().withName("flink").withNamespace(namespace).endMetadata()
        .addNewRule()
            .withApiGroups("")
            .withResources("pods", "configmaps", "services")
            .withVerbs("*")
        .endRule()
        .addNewRule()
            .withApiGroups("apps")
            .withResources("deployments", "deployments/finalizers")
            .withVerbs("*")
        .endRule()
        .build();
    kubernetesClient.rbac().roles().inNamespace(namespace).resource(role).createOrReplace();

    // RoleBinding
    RoleBinding rb = new RoleBindingBuilder()
        .withNewMetadata().withName("flink").withNamespace(namespace).endMetadata()
        .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName("flink").endRoleRef()
        .addNewSubject().withKind("ServiceAccount").withName("flink").withNamespace(namespace).endSubject()
        .build();
    kubernetesClient.rbac().roleBindings().inNamespace(namespace).resource(rb).createOrReplace();
}
```

### 5.4 Fabric8 Client — Applying FlinkDeployment CRD

The `FlinkDeployment` CRD is not a built-in Kubernetes resource, so use the **Generic API**:

```java
public void applyFlinkDeployment(String tenantId, String pipelineId, String flinkDeploymentYaml) {
    String namespace = "tenant-" + tenantId;

    // Load YAML into a GenericKubernetesResource
    try (InputStream is = new ByteArrayInputStream(flinkDeploymentYaml.getBytes(StandardCharsets.UTF_8))) {
        GenericKubernetesResource resource = kubernetesClient.load(is)
            .items()
            .stream()
            .filter(r -> r instanceof GenericKubernetesResource)
            .map(r -> (GenericKubernetesResource) r)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Invalid FlinkDeployment YAML"));

        kubernetesClient.genericKubernetesResources(
            "flink.apache.org/v1beta1", "FlinkDeployment"
        ).inNamespace(namespace).resource(resource).createOrReplace();
    }
}
```

Alternative — use `client.load()` directly:
```java
public void applyFlinkDeployment(String namespace, String yaml) {
    try (InputStream is = new ByteArrayInputStream(yaml.getBytes())) {
        kubernetesClient.load(is).inNamespace(namespace).createOrReplace();
    }
}
```

### 5.5 Fabric8 Client — Watching Job Status

```java
public String getJobStatus(String tenantId, String pipelineId) {
    String namespace = "tenant-" + tenantId;
    String name = "pipeline-" + pipelineId;

    GenericKubernetesResource resource = kubernetesClient
        .genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment")
        .inNamespace(namespace)
        .withName(name)
        .get();

    if (resource == null) return "NOT_FOUND";

    // Navigate the unstructured map
    Map<String, Object> status = (Map<String, Object>) resource.getAdditionalProperties().get("status");
    Map<String, Object> jobStatus = (Map<String, Object>) status.get("jobStatus");
    return (String) jobStatus.get("state");  // RUNNING, FAILED, SUSPENDED, etc.
}
```

### 5.6 Fabric8 Client — Patching FlinkDeployment (suspend/resume)

```java
public void suspendPipeline(String tenantId, String pipelineId) {
    String namespace = "tenant-" + tenantId;
    String name = "pipeline-" + pipelineId;

    // JSON patch to set state: suspended
    String patch = """
        [{"op":"replace","path":"/spec/job/state","value":"suspended"}]
        """;

    kubernetesClient.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment")
        .inNamespace(namespace)
        .withName(name)
        .patch(PatchType.JSON, patch);
}
```

### 5.7 Spring Boot Application Properties

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/pipelines_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    database-platform: org.hibernate.dialect.PostgreSQLDialect

kubernetes:
  in-cluster: true             # Use in-cluster config when running in K8s
  # For local dev, set KUBECONFIG env var instead

platform:
  flink:
    sql-runner-jar: local:///opt/flink/usrlib/sql-runner.jar
    default-image: registry.example.com/flink/sql-runner:1.20
    state-backend-base-path: s3://flink-state
  tenant:
    namespace-prefix: tenant-
```

### 5.8 In-Cluster vs Local Kubernetes Config

```java
@Configuration
public class KubernetesConfig {

    @Value("${kubernetes.in-cluster:false}")
    private boolean inCluster;

    @Bean
    public KubernetesClient kubernetesClient() {
        if (inCluster) {
            return new KubernetesClientBuilder()
                .withConfig(Config.fromCluster())
                .build();
        }
        // Falls back to ~/.kube/config for local development
        return new KubernetesClientBuilder().build();
    }
}
```

---

## 6. Data Model

### 6.1 Core Database Entities

#### Tenant
```
tenant_id        UUID PK
slug             VARCHAR(63) UNIQUE  -- used as namespace suffix
name             VARCHAR(255)
namespace        VARCHAR(253)        -- computed: "tenant-<slug>"
status           ENUM(ACTIVE, SUSPENDED, DELETED)
created_at       TIMESTAMP
```

#### Pipeline
```
pipeline_id      UUID PK
tenant_id        UUID FK → tenant
name             VARCHAR(255)
description      TEXT
sql_query        TEXT                -- the user's SQL SELECT/INSERT statement
status           ENUM(DRAFT, DEPLOYING, RUNNING, SUSPENDED, FAILED, DELETED)
parallelism      INT DEFAULT 1
checkpoint_interval_ms  BIGINT DEFAULT 10000
upgrade_mode     ENUM(SAVEPOINT, LAST_STATE, STATELESS) DEFAULT SAVEPOINT
created_at       TIMESTAMP
updated_at       TIMESTAMP
deployed_at      TIMESTAMP
```

#### PipelineSource (Kafka source config)
```
source_id        UUID PK
pipeline_id      UUID FK → pipeline
table_name       VARCHAR(255)        -- DDL table alias
topic            VARCHAR(255)
bootstrap_servers TEXT
consumer_group   VARCHAR(255)        -- auto-generated: pipeline-<pipeline_id>
startup_mode     ENUM(GROUP_OFFSETS, EARLIEST, LATEST, TIMESTAMP, SPECIFIC)
format           ENUM(JSON, AVRO, AVRO_CONFLUENT, CSV, RAW)
schema_registry_url TEXT
extra_properties JSONB               -- arbitrary Kafka/format properties
watermark_column VARCHAR(255)        -- optional, for event-time processing
watermark_delay_ms BIGINT
```

#### PipelineSink (Kafka sink config)
```
sink_id          UUID PK
pipeline_id      UUID FK → pipeline
table_name       VARCHAR(255)
topic            VARCHAR(255)
bootstrap_servers TEXT
format           ENUM(JSON, AVRO, AVRO_CONFLUENT, CSV, RAW)
schema_registry_url TEXT
partitioner      ENUM(DEFAULT, FIXED, ROUND_ROBIN)
delivery_guarantee ENUM(AT_LEAST_ONCE, EXACTLY_ONCE) DEFAULT AT_LEAST_ONCE
extra_properties JSONB
```

#### PipelineDeployment (tracks K8s state)
```
deployment_id    UUID PK
pipeline_id      UUID FK → pipeline
version          INT                 -- incremented on each redeploy
flink_job_id     VARCHAR(255)        -- filled from status
k8s_resource_name VARCHAR(255)       -- e.g. pipeline-<pipeline_id>
last_savepoint   TEXT                -- s3://... path
lifecycle_state  VARCHAR(50)         -- from FlinkDeployment status
job_state        VARCHAR(50)         -- RUNNING, FAILED, SUSPENDED etc.
last_synced_at   TIMESTAMP
error_message    TEXT
```

### 6.2 REST API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/tenants` | Create tenant + provision K8s namespace |
| GET | `/api/v1/tenants/{tenantId}` | Get tenant details |
| POST | `/api/v1/tenants/{tenantId}/pipelines` | Create and deploy pipeline |
| GET | `/api/v1/tenants/{tenantId}/pipelines` | List pipelines (tenant-scoped) |
| GET | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}` | Get pipeline + live status |
| PUT | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}` | Update pipeline spec (triggers upgrade) |
| POST | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}/suspend` | Suspend job (savepoint) |
| POST | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}/resume` | Resume job |
| DELETE | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}` | Delete pipeline + K8s resource |
| GET | `/api/v1/tenants/{tenantId}/pipelines/{pipelineId}/status` | Live status from FlinkDeployment |

### 6.3 Pipeline Request Schema (POST body)

```json
{
  "name": "Order Count Per Hour",
  "description": "Count orders by customer per hour",
  "sqlQuery": "INSERT INTO sink_table SELECT ...",
  "source": {
    "tableName": "orders_source",
    "topic": "orders-raw",
    "bootstrapServers": "kafka:9092",
    "startupMode": "EARLIEST",
    "format": "JSON",
    "watermarkColumn": "event_time",
    "watermarkDelayMs": 5000
  },
  "sink": {
    "tableName": "order_counts",
    "topic": "order-counts-output",
    "bootstrapServers": "kafka:9092",
    "format": "JSON",
    "deliveryGuarantee": "AT_LEAST_ONCE"
  },
  "parallelism": 2,
  "checkpointIntervalMs": 10000,
  "upgradeMode": "SAVEPOINT"
}
```

---

## 7. SQL Generation Strategy

The platform must generate a `statements.sql` file from the pipeline spec stored in the database.

### 7.1 SQL Generation Steps

1. **Generate source CREATE TABLE** from `PipelineSource` entity
2. **Generate sink CREATE TABLE** from `PipelineSink` entity
3. **Append the user's SQL query** (the `sqlQuery` field)

### 7.2 SQL Sanitization Concerns

- The user-provided `sqlQuery` must be validated before storage and execution
- Reject DDL statements (CREATE TABLE, DROP, ALTER) in the query field — the platform generates those
- The query should only contain SELECT or INSERT INTO targeting the declared table aliases
- Validate that table names referenced in the query match declared source/sink table names
- Consider a SQL parser (Apache Calcite is embedded in Flink — however for pre-validation, use a lightweight parser like `JSQLParser`)

### 7.3 ConfigMap-based SQL Delivery

```java
public void createSqlConfigMap(String namespace, String pipelineId, String sqlContent) {
    ConfigMap cm = new ConfigMapBuilder()
        .withNewMetadata()
            .withName("pipeline-" + pipelineId + "-sql")
            .withNamespace(namespace)
            .addToLabels("pipeline-id", pipelineId)
        .endMetadata()
        .addToData("statements.sql", sqlContent)
        .build();

    kubernetesClient.configMaps().inNamespace(namespace).resource(cm).createOrReplace();
}
```

---

## 8. Operational Concerns

### 8.1 State Backend and Storage

For production, checkpoints and savepoints must be written to durable shared storage:
- **S3-compatible** (AWS S3, MinIO): Most common; requires Flink S3 filesystem plugin
- **PersistentVolumeClaim**: Simpler but not shared across pods; not suitable for HA

flinkConfiguration for S3:
```
state.backend.type: rocksdb
state.checkpoints.dir: s3://flink-state/<tenant>/<pipeline>/checkpoints
state.savepoints.dir: s3://flink-state/<tenant>/<pipeline>/savepoints
s3.endpoint: https://s3.amazonaws.com
s3.access-key: <key>
s3.secret-key: <secret>
```

### 8.2 High Availability

For production FlinkDeployments:
- Set `jobManager.replicas: 2`
- Enable `high-availability.type: org.apache.flink.kubernetes.highavailability.KubernetesHaServicesFactory`
- Configure `high-availability.storageDir` (S3 or PVC path)

### 8.3 Status Synchronization

The REST API should periodically sync pipeline status from the `FlinkDeployment` status into the database:
- Use a scheduled Spring task (`@Scheduled`) or a Kubernetes informer/watch
- Watch for `FlinkDeployment` status changes using `kubernetesClient.genericKubernetesResources(...).watch(watcher)`
- Update `PipelineDeployment.job_state` and `lifecycle_state` in the database

### 8.4 Docker Image Strategy

Two viable strategies:

| Strategy | Description | Tradeoffs |
|----------|-------------|-----------|
| **Single base image + ConfigMap** | SQL runner JAR in a static image; SQL in ConfigMap | Fast deploys, no CI/CD per pipeline |
| **Per-pipeline image** | SQL baked into image at deploy time | Full immutability, audit trail; requires image build infra |

**Recommended**: Single base image + ConfigMap for the REST API use case.

The base image only needs to be rebuilt when:
- Flink version is upgraded
- Additional SQL functions/JARs need to be added

### 8.5 API Authentication (Tenant Isolation)

The REST API must enforce tenant isolation at the API layer:
- Each API request must carry a tenant identifier (JWT claim, API key, or path parameter validated against authenticated session)
- Service layer must verify the pipeline `tenant_id` matches the authenticated tenant before any K8s operation
- Never expose raw K8s namespace names to clients

---

## 9. Technology Stack Summary

| Layer | Technology | Version Recommendation |
|-------|-----------|------------------------|
| API Framework | Spring Boot | 3.x |
| Kubernetes Client | Fabric8 kubernetes-client | 6.x |
| Database | PostgreSQL | 15+ |
| ORM | Spring Data JPA + Hibernate | (with Spring Boot 3) |
| Flink | Apache Flink | 1.20 |
| Flink Operator | Flink Kubernetes Operator | 1.13+ |
| SQL Runner | flink-sql-runner-example JAR | From operator examples |
| State Backend | RocksDB + S3 | (for production) |
| Kafka | Apache Kafka | 3.x |
| Container Registry | Any OCI registry | (for base image) |
| Build | Maven or Gradle | Maven preferred for Spring Boot |

---

## 10. Key Technical Decisions for Builders

1. **SQL delivery via ConfigMap** (not image-per-pipeline) — eliminates Docker build pipeline dependency for the REST API
2. **One FlinkDeployment per pipeline** (Application Mode) — maximum isolation; simpler lifecycle management; slightly higher resource overhead than Session Mode
3. **Cluster-scoped Flink Operator** — allows dynamic tenant namespace creation without operator reconfiguration; API creates RBAC resources per namespace on tenant onboarding
4. **Fabric8 Generic API** for FlinkDeployment — no need for custom CRD POJOs; YAML can be generated as a string and applied via `client.load()`
5. **Upgrade mode: SAVEPOINT** as default — safe state preservation on all updates; requires S3/durable storage for checkpoint/savepoint directories
6. **SQL validation** at API layer before persistence — reject DDL in user-provided SQL query field; validate table name references
7. **Status synchronization** via K8s watcher + `@Scheduled` fallback — keep database in sync with actual job state
8. **Tenant namespace naming**: `tenant-<slug>` where slug is URL-safe lowercase alphanumeric with hyphens

---

## Sources

- [Apache Flink Kafka Connector Docs](https://nightlies.apache.org/flink/flink-docs-master/docs/connectors/table/kafka/)
- [Apache Flink Upsert Kafka Connector](https://nightlies.apache.org/flink/flink-docs-master/docs/connectors/table/upsert-kafka/)
- [Flink Kubernetes Operator Overview](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/)
- [Flink Kubernetes Operator CRD Reference](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/reference/)
- [Flink Kubernetes Operator RBAC Model](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/operations/rbac/)
- [Flink Kubernetes Operator Job Management](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/job-management/)
- [Flink SQL Runner Example (GitHub)](https://github.com/apache/flink-kubernetes-operator/tree/main/examples/flink-sql-runner-example)
- [Flink SQL Window Aggregations](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/table/sql/queries/window-agg/)
- [Flink SQL Windowing TVFs](https://nightlies.apache.org/flink/flink-docs-master/docs/dev/table/sql/queries/window-tvf/)
- [IBM Event Automation — SQL Runner Production Deployment](https://ibm.github.io/event-automation/ep/advanced/deploying-production/)
- [Fabric8 Kubernetes Client GitHub](https://github.com/fabric8io/kubernetes-client)
- [Programming Kubernetes Custom Resources in Java](https://developers.redhat.com/articles/2023/01/04/programming-kubernetes-custom-resources-java)
- [Multi-Tenancy Kubernetes Best Practices](https://kubernetes.io/docs/concepts/security/multi-tenancy/)
- [Confluent Avro Format for Flink](https://nightlies.apache.org/flink/flink-docs-release-1.18/docs/connectors/table/formats/avro-confluent/)
