# Social Posts: Kubernetes Local Dev Environment

---

## Twitter / X

**Option A (technical)**
```
We just shipped Kubernetes-based local dev for our Flink SQL Pipeline Platform.

make k8s-setup  # cert-manager + Flink Operator
make k8s-up     # build, deploy, ready

No registry. No port-forwarding per pipeline. Nginx proxy routes /flink/{tenant}/{pipeline}/ in-cluster.

Docker Compose still works. Both modes coexist.
```

**Option B (punchy)**
```
Running Flink pipelines locally used to mean:
- kind cluster
- manual port-forward per pipeline
- Docker Compose for everything else

Now: make k8s-up → full K8s stack on Docker Desktop, Flink UI accessible via Nginx proxy.

No registry. No port-forward. Both modes live side-by-side.
```

---

## LinkedIn

```
We've upgraded our local development experience for the Flink SQL Pipeline Platform.

Previously, local dev meant Docker Compose — great for most things, but it meant:
• No Kubernetes namespace isolation
• No TenantNamespaceProvisioner behaviour to test
• Manual kubectl port-forward for every Flink job's web UI

We've now added a full Kubernetes mode on top of Docker Desktop's built-in cluster:

✅ make k8s-setup — one-time bootstrap (cert-manager + Flink Kubernetes Operator via Helm)
✅ make k8s-up — builds images, applies manifests, waits for readiness, prints access URLs
✅ Nginx Flink proxy — routes /flink/{tenant}/{pipeline}/ to the right job in-cluster, no port-forward
✅ make k8s-status / make k8s-down — operational helpers
✅ make up (Docker Compose) still works — both modes coexist

The topology mirrors production: ns-enterprise (Kafka, Keycloak, MinIO), ns-controlplane (backend, frontend), tenant-* (Flink jobs provisioned dynamically).

No registry needed — Docker Desktop shares the Docker daemon so imagePullPolicy: Never just works.

Small change to the developer workflow. Big improvement in how closely local testing reflects real deployments.
```
