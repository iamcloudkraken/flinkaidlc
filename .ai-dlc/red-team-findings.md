# Red Team Findings: unit-01-data-model-and-foundation

## Findings

### CRITICAL

_None identified._

### HIGH

- **Unauthenticated tenant registration with no rate limiting or authorization**: `POST /api/v1/tenants` is permitted to all without any authentication. In a multi-tenant SaaS context this is a significant abuse vector — any actor on the internet can create arbitrary tenants, exhaust DB/namespace quota, and enumerate slugs or `fid` values via timing/error responses. There is no rate limiting, CAPTCHA, API key, or admin-token guard in place. [SecurityConfig.java:23]

- **`tenant_id` claim is trusted without cross-validating against the registered tenant store**: `TenantJwtAuthenticationConverter` extracts `tenant_id` directly from the JWT payload and builds a `TenantAuthenticationPrincipal` without ever checking that the UUID corresponds to an existing, `ACTIVE` tenant. A valid JWT issued by the correct IdP for a deleted or suspended tenant will still authenticate successfully. Downstream services that rely on `@AuthenticationPrincipal` for row-level isolation receive a dangling `tenantId` that may match no DB row, or — more dangerously — if a tenant is re-created with the same UUID, old JWTs regain access. [TenantJwtAuthenticationConverter.java:19-26]

- **`anyRequest().permitAll()` catch-all exposes non-API surface unauthenticated**: The security filter chain applies `.anyRequest().permitAll()` as the terminal rule. All actuator endpoints not explicitly listed (`/actuator/metrics`, `/actuator/env`, `/actuator/loggers`, `/actuator/threaddump`, `/actuator/heapdump`, `/actuator/mappings`, etc.) are exposed without authentication if the `management.endpoints.web.exposure.include` property is ever expanded or left at its default in a misconfigured deployment. Even though `application.yml` currently limits exposure to `health,info`, this defence relies solely on the actuator configuration rather than the security config — a defence-in-depth gap. [SecurityConfig.java:27]

### MEDIUM

- **`IllegalArgumentException` from `UUID.fromString()` not caught — results in 500 for malformed `tenant_id` claim**: If a JWT contains a `tenant_id` claim that is not a valid UUID string, `UUID.fromString()` throws `IllegalArgumentException`. This is not handled by `TenantJwtAuthenticationConverter` or the `GlobalExceptionHandler`, so it propagates as an unhandled 500 response that may expose internal stack traces via the default Spring error response (contains `exception`, `trace` fields when `server.error.include-stacktrace=always`). A `BadCredentialsException` should be thrown instead. [TenantJwtAuthenticationConverter.java:24]

- **Exception handler passes raw exception messages to `ProblemDetail.detail`**: `handleEntityNotFoundException`, `handleValidationException`, `handleAccessDeniedException`, and `handleResourceLimitExceededException` all set `ex.getMessage()` directly as the `detail` field. If exception messages ever contain internal paths, DB table names, or query fragments (e.g., from Hibernate's `EntityNotFoundException`), these leak in API responses. No sanitisation or message canonicalisation is applied. [GlobalExceptionHandler.java:20, 30, 40, 50]

- **`Pipeline.tenantId` is not a JPA association — no referential integrity enforced at the ORM layer**: `Pipeline.tenantId` is mapped as a plain `@Column(name = "tenant_id")` UUID, not as `@ManyToOne` to `Tenant`. The DB constraint (`REFERENCES tenants(tenant_id)`) enforces integrity at the SQL level, but there is no ORM-level guard. Any service-layer code that constructs a `Pipeline` and sets `tenantId` to an arbitrary value will not receive a validation error until the DB write; the error will bubble up as a generic constraint violation rather than a domain-meaningful exception. This also means no lazy-loading guard exists — nothing prevents cross-tenant data leakage if code accidentally resolves a `Pipeline` record belonging to another tenant by passing an unchecked UUID. [Pipeline.java:26-27]

- **`extraProperties` is a raw JSON string stored in a `jsonb` column with no schema validation**: `PipelineSource.extraProperties` accepts arbitrary JSON text (`String`) without parsing, schema validation, or size capping before storage. If this field is ever exposed through an API endpoint an attacker can store arbitrarily large payloads, or craft values that confuse downstream Flink SQL config generation (property injection). [PipelineSource.java:55-56]

- **Spring Boot 3.2.5 is not the latest 3.2.x patch**: Spring Boot 3.2.5 was released in May 2024. Subsequent 3.2.x releases addressed several CVEs, including Spring Framework path-traversal issues (CVE-2024-38816 fixed in 3.2.10) and Spring Security authentication bypass issues. The project should upgrade to at least 3.2.12 (the last 3.2.x release) or migrate to the 3.3/3.4 line. [pom.xml:10]

### LOW / INFORMATIONAL

- **`TenantJwtAuthenticationToken` grants zero authorities (`Collections.emptyList()`)**: The token is constructed with an empty authority list. All role/scope-based access control (`hasRole`, `hasAuthority`) is therefore permanently unavailable. As soon as endpoint-level RBAC is needed (e.g., distinguishing admin vs. member within a tenant) the entire auth model must be reworked. This should be a deliberate design decision documented, not left as an oversight. [TenantJwtAuthenticationToken.java:17]

- **`jwt.tenant-id-claim` is configurable via application properties**: The claim name `tenant_id` can be overridden at runtime via `${jwt.tenant-id-claim}`. While this adds flexibility, it creates an operational risk: a misconfigured deployment could silently use a different claim name and either always fail authentication or, if the alternative claim name exists in the token with a different semantics, allow unexpected access. [TenantJwtAuthenticationConverter.java:15-16, application.yml:33-34]

- **No `@NotNull` / `@NotBlank` / `@Email` Bean Validation annotations on entity fields**: `Tenant.slug`, `Tenant.contactEmail`, `Tenant.name`, `Tenant.fid`, and analogous `Pipeline` fields carry `nullable=false` at the JPA level but no JSR-380 (`jakarta.validation`) annotations. Validation only fires at the DB write. Service-layer or REST layer input validation is entirely absent in this unit, meaning malformed inputs (empty strings, non-email contact addresses) are only caught by the DB constraint, producing opaque 500 errors rather than 400 responses. [Tenant.java, Pipeline.java]

- **Fabric8 6.13.1 — no known critical CVEs at review time, but watch list**: Fabric8 6.13.1 is a recent release. No critical CVEs were known as of the review date (March 2026). However, Fabric8 has historically had deserialization and SSRF issues in its HTTP client (e.g., CVE-2023-33246 affected related ecosystem). The project should subscribe to `io.fabric8:kubernetes-client` advisories and enforce a renovation policy.

- **`Tenant.maxPipelines` and `Tenant.maxTotalParallelism` are settable via Lombok `@Setter`**: These are resource-limit fields that should only be modifiable by an admin/billing system, but the generated Lombok setter exposes them as public methods on the entity. Any code with a reference to a `Tenant` object can silently override the limits. If these entities are ever returned from a REST endpoint that is also writable (e.g., a PUT/PATCH), mass-assignment of these fields becomes a privilege escalation vector. [Tenant.java:15, 41-44]

- **Test JWT built with `alg: "none"` header**: `TestSecurityConfig` and `TenantJwtAuthenticationConverterTest` construct JWTs with `header("alg", "none")`. While this is test-only code, if the `TestConfiguration` were inadvertently included in a non-test Spring context (e.g., via component scan misconfiguration), the mock `JwtDecoder` would accept any token. The risk is low but the pattern should be documented. [TestSecurityConfig.java:25, TenantJwtAuthenticationConverterTest.java:22]

- **No `@Transactional(readOnly=true)` discipline in repositories**: Repositories expose full `JpaRepository` write operations publicly without any service-layer guard. There is no read-only query differentiation. This is not a security issue in isolation but enables accidental data mutation through repository misuse.

- **`contact_email` column has no format constraint at the DB level**: The schema accepts any `VARCHAR(255)` value for `contact_email`. No `CHECK` constraint enforces email format. This is defense-in-depth only but is worth noting for data integrity.

## Verdict

**FAIL** — Two HIGH severity issues require remediation before this unit is promoted:

1. Unauthenticated, rate-unlimited `POST /api/v1/tenants` is an abuse vector that must be gated (admin token, rate limit, or explicit architectural decision with documented risk acceptance).
2. `tenant_id` from JWT is never validated against the live tenant store, allowing stale/suspended tenant JWTs to authenticate and dangling-UUID scenarios to arise.

The `UUID.fromString` unhandled exception (MEDIUM) also needs fixing to avoid 500 leakage on malformed tokens.
