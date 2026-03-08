---
status: pending
depends_on: [unit-02-tenant-onboarding-api, unit-03-pipeline-management-api]
branch: ai-dlc/flink-sql-pipeline-platform/05-web-ui
discipline: frontend
workflow: ""
ticket: ""
---

# unit-05-web-ui

## Description
A React + TypeScript single-page application providing self-service tenant onboarding and pipeline management. The UI consumes the REST API from units 02 and 03. Deployed as a standalone Docker image and Kubernetes service.

## Discipline
frontend — executed by frontend-focused agents.

## Domain Entities
**Tenant** — onboarding form, resource usage dashboard. **Pipeline** — list, detail, create/edit form, lifecycle actions. **PipelineSource** / **PipelineSink** — configured as sub-forms within the pipeline form.

## Data Sources
- **Spring Boot REST API** (units 02 + 03): all data via HTTP. Base URL from env var `VITE_API_BASE_URL`
- **OAuth2 Provider**: token exchange — `POST {VITE_OAUTH2_TOKEN_URL}` with FID + secret → JWT. Token stored in memory (not localStorage) for security.

## Technical Specification

### Project Setup
- Vite + React 18 + TypeScript 5
- Dependencies: `react-router-dom` (v6), `@tanstack/react-query` (data fetching + caching), `monaco-editor` (SQL editor), `axios` (HTTP client), a UI component library (Shadcn/ui or MUI — builder's choice)
- Build: `npm run build` → `dist/` served by nginx
- Docker: multi-stage build — `node:20-alpine` build stage, `nginx:alpine` serve stage
- Env vars (build-time via Vite): `VITE_API_BASE_URL`, `VITE_OAUTH2_TOKEN_URL`

### Routes

| Path | Component | Auth required |
|---|---|---|
| `/` | Redirect to `/login` or `/pipelines` | — |
| `/register` | TenantRegistrationPage | No |
| `/login` | LoginPage | No |
| `/pipelines` | PipelineListPage | Yes |
| `/pipelines/new` | PipelineEditorPage (create mode) | Yes |
| `/pipelines/:id` | PipelineDetailPage | Yes |
| `/pipelines/:id/edit` | PipelineEditorPage (edit mode) | Yes |
| `/account` | TenantDashboardPage | Yes |

### Pages and Components

**TenantRegistrationPage** (`/register`)
- Form fields: Organization Name, Slug (auto-derived from name, editable), Contact Email, Max Pipelines, Max Parallelism
- On submit: `POST /api/v1/tenants`
- On success: show FID and secret in a one-time modal with "Copy to clipboard" — warn user it cannot be retrieved again
- Client-side validation: slug format `^[a-z0-9-]{3,63}$`, email format, numeric limits ≥ 1

**LoginPage** (`/login`)
- Form: FID, FID Secret
- On submit: `POST {VITE_OAUTH2_TOKEN_URL}` with `grant_type=client_credentials`, `client_id={fid}`, `client_secret={secret}`
- Store JWT in memory (`AuthContext`) — NOT in localStorage or cookies
- Redirect to `/pipelines` on success

**PipelineListPage** (`/pipelines`)
- Table columns: Name, Status (badge with color), Parallelism, Created At, Actions (View, Suspend/Resume, Delete)
- Status badge colors: RUNNING=green, DEPLOYING=blue, SUSPENDED=yellow, FAILED=red, DRAFT=gray
- Polling: `useQuery` with `refetchInterval: 10000` to show live status updates
- "New Pipeline" button → `/pipelines/new`
- Delete action: confirmation dialog → `DELETE /api/v1/pipelines/{id}`
- Suspend/Resume actions: call respective endpoints, optimistic UI update

**PipelineDetailPage** (`/pipelines/:id`)
- Shows: name, status badge, SQL query (read-only Monaco editor), parallelism, checkpoint interval, upgrade mode
- Sources section: table showing tableName, topic, bootstrapServers, schemaRegistryUrl, avroSubject
- Sinks section: same structure
- Deployment info: lifecycleState, jobState, lastSavepointPath, errorMessage (if FAILED)
- Action buttons: Edit, Suspend/Resume, Delete (with confirmation)

**PipelineEditorPage** (`/pipelines/new` and `/pipelines/:id/edit`)
- Step 1 — Basic Info: name, description, parallelism (1-N), checkpointIntervalMs, upgradeMode (select)
- Step 2 — SQL Query: Monaco editor with Flink SQL syntax highlighting, min height 300px; inline "Validate SQL" button that calls a client-side JSQLParser-equivalent check (reject DDL keywords)
- Step 3 — Sources: dynamic list, "Add Source" button. Per source: tableName, topic, bootstrapServers, consumerGroup, startupMode (select), schemaRegistryUrl, avroSubject, watermarkColumn (optional), watermarkDelayMs
- Step 4 — Sinks: dynamic list, "Add Sink" button. Per sink: tableName, topic, bootstrapServers, schemaRegistryUrl, avroSubject, partitioner (select), deliveryGuarantee (select)
- Step 5 — Review: summary of all fields
- Submit: `POST /api/v1/pipelines` (create) or `PUT /api/v1/pipelines/:id` (edit)
- On success: redirect to `/pipelines/:id`
- On API error: display `detail` field from `application/problem+json` response inline

**TenantDashboardPage** (`/account`)
- Calls `GET /api/v1/tenants/{tenantId}` (tenantId from JWT `tenant_id` claim)
- Shows: org name, slug, contact email, status
- Resource usage: progress bars for "Pipelines: X of Y" and "Parallelism: X of Y"

### Auth Context
`AuthContext` (React Context):
- Stores JWT in memory: `{ token: string, tenantId: UUID, expiresAt: number }`
- `useAuth()` hook: exposes `{ login, logout, token, tenantId, isAuthenticated }`
- Axios interceptor: attaches `Authorization: Bearer {token}` to all API requests
- On 401 response: clear token, redirect to `/login`
- Token expiry: check `expiresAt` before each request; redirect to `/login` if expired

### API Client Layer
`src/api/` directory with typed API functions:
- `tenants.ts`: `registerTenant`, `getTenant`, `updateTenant`, `deleteTenant`
- `pipelines.ts`: `listPipelines`, `getPipeline`, `createPipeline`, `updatePipeline`, `deletePipeline`, `suspendPipeline`, `resumePipeline`
- All functions return typed response objects matching the API DTOs
- Error handling: extract `detail` from `application/problem+json` responses

### Kubernetes Deployment
`k8s/` directory in the UI repo:
- `Deployment`: 2 replicas, image from registry, env vars `VITE_API_BASE_URL` and `VITE_OAUTH2_TOKEN_URL` passed as build args
- `Service`: ClusterIP on port 80
- `Ingress`: expose at `/` (platform backend at `/api`)
- nginx config: `try_files $uri /index.html` for SPA routing

## Success Criteria
- [ ] Tenant registration form creates tenant via `POST /api/v1/tenants` and displays FID + secret in a one-time modal
- [ ] Login form exchanges FID + secret for JWT; JWT stored in memory only (not localStorage/sessionStorage)
- [ ] Pipeline list shows all tenant pipelines with correct status badges; auto-refreshes every 10s
- [ ] Monaco SQL editor loads on PipelineEditorPage with Flink SQL syntax highlighting
- [ ] Create pipeline multi-step form submits valid request to `POST /api/v1/pipelines`; API error `detail` displayed inline
- [ ] Suspend/Resume buttons call correct endpoints; pipeline status updates optimistically in list
- [ ] Delete with confirmation dialog calls `DELETE /api/v1/pipelines/{id}`; pipeline removed from list
- [ ] TenantDashboardPage shows resource usage progress bars with correct values from API
- [ ] `npm run build` produces a working production build; Docker image builds and serves the app via nginx
- [ ] JWT 401 response redirects user to `/login`

## Risks
- **JWT in-memory loss on refresh**: storing JWT in memory means page refresh requires re-login. Mitigation: document this as a known UX trade-off for security; consider silent refresh with refresh token in v2.
- **Monaco editor bundle size**: Monaco adds ~5MB to the bundle. Mitigation: use dynamic import (`React.lazy`) to code-split the editor.
- **CORS**: if UI and API are on different origins, CORS must be configured on the Spring Boot backend. Mitigation: add `spring.web.cors.allowed-origins` config; document required setup.
- **Schema Registry URL free-text input**: users can type any URL including internal endpoints — SSRF risk is on the backend (unit-03 handles validation). UI should show a warning: "This URL will be validated by the platform before deployment."

## Boundaries
This unit does NOT implement any backend logic. It does NOT validate SQL against Flink runtime (that is the backend's job). It does NOT manage Kubernetes resources. Pipeline status displayed is fetched from the API — no direct K8s connection from the browser.

## Notes
- Use React Query's `useMutation` for all write operations — enables optimistic updates and automatic error handling
- Use `react-hook-form` for all forms — reduces boilerplate and provides good validation UX
- All status transitions that require confirmation (delete, suspend of running pipeline) must use a modal dialog — never inline destructive actions
- The multi-step pipeline editor should save form state in React state (not URL) so the user doesn't lose progress on step navigation
