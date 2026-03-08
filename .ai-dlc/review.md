# Review: unit-01-data-model-and-foundation

## Criteria

- [x] **Criterion 1 — Flyway migrations apply cleanly; all 5 tables created with correct constraints**
  Three migration files cover all 5 tables: V1 (tenants), V2 (pipelines, pipeline_sources, pipeline_sinks), V3 (pipeline_deployments). All primary keys, NOT NULL constraints, FK references with CASCADE DELETE, UNIQUE constraints (slug, fid), DEFAULT values, and TIMESTAMPTZ columns are correctly defined. `FlywayMigrationIntegrationTest` verifies all 5 table names exist and checks every required column via `information_schema`. DDL is correct.

- [x] **Criterion 2 — JPA entities persist/retrieve correctly in integration tests**
  All five entities (`Tenant`, `Pipeline`, `PipelineSource`, `PipelineSink`, `PipelineDeployment`) are properly annotated with `@Entity`, correct `@Table`, `@Column`, `@Enumerated`, `@UuidGenerator`, and lifecycle callbacks (`@PrePersist`/`@PreUpdate`). `EntityPersistenceIntegrationTest` uses a real Postgres 16 container (via Testcontainers), flushes/clears the `EntityManager`, and asserts round-trip fidelity. `PipelineDeployment` correctly uses `@MapsId` with a shared PK.

  **BUG (compile-time failure):** `EntityPersistenceIntegrationTest` lines 39–40 call `tenant.setMaxPipelines(20)` and `tenant.setMaxTotalParallelism(100)`, but the blue-team fix added `@Setter(AccessLevel.NONE)` to both fields on `Tenant` (removing those setters). The test will not compile. The test must be updated to use `tenant.updateQuota(20, 100)` instead.

- [x] **Criterion 3 — `POST /api/v1/tenants` accessible without auth; all other `/api/**` return 401 without JWT**
  `SecurityConfig` permits `POST /api/v1/tenants` (when `registration.enabled=true`, the default), requires authentication for all `/api/**`, and uses `denyAll()` as the catch-all. Spring Security's OAuth2 resource server returns 401 automatically for missing/invalid JWTs. `SecurityIntegrationTest` asserts that `POST /api/v1/tenants` does not return 401, that `GET /api/v1/pipelines` returns 401 without a token, and that a valid (mocked) JWT is not rejected. Logic is correct.

- [x] **Criterion 4 — Valid JWT with `tenant_id` claim populates `TenantAuthenticationPrincipal`**
  `TenantJwtAuthenticationConverter` extracts the `tenant_id` claim, parses it as UUID (wrapping `IllegalArgumentException` in `BadCredentialsException` — blue-team fix), and constructs a `TenantAuthenticationPrincipal` record. `TenantJwtAuthenticationToken` overrides `getPrincipal()` to return the `TenantAuthenticationPrincipal`, making it available via `@AuthenticationPrincipal`. Unit tests in `TenantJwtAuthenticationConverterTest` cover the happy path, missing claim, and malformed UUID (no info leakage, no cause attached). Implementation is correct and secure.

- [x] **Criterion 5 — `KubernetesClient` bean initializes without error**
  `KubernetesConfig` creates a `KubernetesClient` using `KubernetesClientBuilder().build()` (auto-detects in-cluster or kubeconfig). Also exposes a `MixedOperation` bean for `FlinkDeployment` CRDs. `KubernetesClientBeanTest` autowires both beans and asserts non-null. The Fabric8 client is lazy about connectivity, so bean creation succeeds without a live cluster — correct for a unit foundation test.

- [x] **Criterion 6 — All error responses use `Content-Type: application/problem+json`**
  `GlobalExceptionHandler` sets `MediaType.APPLICATION_PROBLEM_JSON` explicitly on all four `ResponseEntity` responses (404, 403, 400, 429). The handler uses Spring 6's `ProblemDetail`. `GlobalExceptionHandlerIntegrationTest` uses `@WebMvcTest` + `MockMvc` to assert `content().contentType(MediaType.APPLICATION_PROBLEM_JSON)` and correct JSON field values (`status`, `title`, `detail`) for all four exception types. Safe message sanitisation is in place (generic strings for `EntityNotFoundException` and `AccessDeniedException`; application-defined messages for `ValidationException` and `ResourceLimitExceededException`). Correct.

## Issues Found

### BLOCKING

1. **Compile failure in `EntityPersistenceIntegrationTest`** — `tenant.setMaxPipelines(20)` and `tenant.setMaxTotalParallelism(100)` (lines 39–40) call setters that no longer exist after the blue-team introduced `@Setter(AccessLevel.NONE)`. The test will fail to compile. Fix: replace both calls with `tenant.updateQuota(20, 100)` and assert against `100` and `20` respectively in the assertions block.

### NON-BLOCKING (deferred by design or minor)

2. **`tenant_id` JWT claim not cross-validated against the tenant store** — Red team flagged this as HIGH. The blue-team commit message and `SecurityConfig` Javadoc acknowledge the scope deferral: tenant-existence validation belongs in the service layer (unit-02). This is an accepted deferral, not a gap in unit-01's scope, but must be tracked for unit-02.

3. **`GlobalExceptionHandler` has no catch-all `@ExceptionHandler(Exception.class)`** — Unhandled exceptions (e.g., from Fabric8 or unexpected JPA errors) will fall through to Spring Boot's default `BasicErrorController`, which returns `application/json` rather than `application/problem+json`. This is a gap but is out of the stated success criteria for this unit.

4. **`FlywayMigrationIntegrationTest` uses string concatenation for SQL in `getColumnsForTable`** — SQL injection is not a real risk in tests, but the pattern is worth flagging for hygiene.

5. **`SecurityIntegrationTest.getAnyApiEndpointWithValidJwtReturnsNotUnauthorized`** — Asserts `isNotEqualTo(UNAUTHORIZED)` which passes even if the server returns 500. A stricter assertion (e.g., `isIn(404, 200)`) would be more meaningful, but this is a test-quality concern, not a functional gap.

## Verdict

**REJECTED**

One blocking compile error exists: `EntityPersistenceIntegrationTest` calls `setMaxPipelines()` and `setMaxTotalParallelism()` which were removed by the blue-team security fix. The build will not compile as-is. All other criteria are satisfied and the security fixes are correctly applied.

**Required fix before re-review:**
In `EntityPersistenceIntegrationTest.persistAndRetrieveTenant()`, replace:
```java
tenant.setMaxPipelines(20);
tenant.setMaxTotalParallelism(100);
```
with:
```java
tenant.updateQuota(20, 100);
```

## Merge Readiness

Not Ready — blocked by compile failure in `EntityPersistenceIntegrationTest`.
