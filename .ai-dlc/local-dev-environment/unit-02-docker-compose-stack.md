---
status: pending
depends_on: [unit-01-spring-local-profile]
branch: ai-dlc/local-dev-environment/02-docker-compose-stack
discipline: devops
workflow: ""
ticket: ""
---

# unit-02-docker-compose-stack

## Description

Create a Docker Compose stack that starts the entire Flink SQL Pipeline Platform locally with a single `docker compose up` command. Includes all infrastructure services, a backend container running with the `local` Spring profile (from unit-01), the React frontend via nginx, Keycloak with a pre-imported realm, and a seed data SQL script that pre-creates a demo tenant and sample pipeline.

## Discipline

devops - This unit will be executed by devops-focused agents.

## Domain Entities

- **Tenant** — seed script creates one demo tenant with `tenant_id = "00000000-0000-0000-0000-000000000001"` (matches mock JWT in unit-01), `slug = "demo"`, `name = "Demo Org"`, `status = ACTIVE`
- **Pipeline** — seed script creates one sample pipeline in DRAFT status with a trivial Flink SQL query

## Data Sources

- `src/main/resources/application-local.properties` (from unit-01) — provides all config keys the backend container needs
- `frontend/Dockerfile` — existing multi-stage build (node:20-alpine + nginx:1.25-alpine); nginx proxies `/api/` to backend
- `frontend/nginx.conf` — existing SPA config with `/api/` proxy to `flink-platform-backend:8080`
- Flyway migrations in `src/main/resources/db/migration/` — defines table schemas for seed data INSERT statements

## Technical Specification

### File structure to create

```
docker-compose.yml                 # Main compose file
docker/
  backend/
    Dockerfile                     # Spring Boot app image
  keycloak/
    realm-export.json              # Keycloak realm with flink-platform client
  postgres/
    seed.sql                       # Demo tenant + pipeline INSERT statements
Makefile                           # make up / make down / make logs
```

### `docker-compose.yml`

Services (in startup order):

```yaml
version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: flinkplatform
      POSTGRES_USER: flinkplatform
      POSTGRES_PASSWORD: flinkplatform
    ports: ["5432:5432"]
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./docker/postgres/seed.sql:/docker-entrypoint-initdb.d/99-seed.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U flinkplatform"]
      interval: 5s
      timeout: 3s
      retries: 10

  keycloak:
    image: quay.io/keycloak/keycloak:24.0
    command: start-dev --import-realm
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports: ["8080:8080"]   # NOTE: backend uses 8090 to avoid collision — see backend service
    volumes:
      - ./docker/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/realms/flink-platform/.well-known/openid-configuration || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 12

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports: ["2181:2181"]

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_INTERNAL://kafka:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_INTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT_INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions --bootstrap-server localhost:9092"]
      interval: 10s
      retries: 5

  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.0
    depends_on: [kafka]
    ports: ["8082:8081"]   # Exposed on host 8082; internal port 8081
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:29092
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081

  backend:
    build:
      context: .
      dockerfile: docker/backend/Dockerfile
    ports: ["8090:8090"]   # Backend on 8090 to avoid Keycloak port collision
    depends_on:
      postgres: {condition: service_healthy}
      keycloak: {condition: service_healthy}
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/flinkplatform
      SPRING_DATASOURCE_USERNAME: flinkplatform
      SPRING_DATASOURCE_PASSWORD: flinkplatform
      SERVER_PORT: 8090
      # OAuth2 issuer pointing to local Keycloak
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://keycloak:8080/realms/flink-platform
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8090/actuator/health || exit 1"]
      interval: 10s
      retries: 10

  frontend:
    build:
      context: frontend
      dockerfile: Dockerfile
    ports: ["3000:80"]
    depends_on:
      backend: {condition: service_healthy}
    environment:
      BACKEND_HOST: backend
      BACKEND_PORT: 8090

volumes:
  postgres_data:
```

**Note on ports**: Keycloak takes 8080, backend runs on 8090, frontend on 3000. The nginx proxy in the frontend container must proxy `/api/` to `backend:8090`.

### `docker/backend/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
```

This requires the JAR to be built before `docker compose up`. The Makefile handles this.

### `docker/keycloak/realm-export.json`

Create a minimal Keycloak realm named `flink-platform` with:
- Realm name: `flink-platform`
- One client: `flink-platform-client` (public, standard flow enabled, redirect URIs: `http://localhost:3000/*`)
- JWT claims mapping: add a `tenant_id` attribute mapper that reads from the user attribute `tenant_id` and maps to the token claim `tenant_id`
- One test user: `dev@local.dev` / password `dev123`, with `tenant_id` attribute = `00000000-0000-0000-0000-000000000001`

This allows the frontend login flow to work end-to-end with real JWTs while the backend's local mock JwtDecoder accepts any token.

### `docker/postgres/seed.sql`

Runs after Flyway migrations (via `docker-entrypoint-initdb.d/99-seed.sql` — note: Flyway runs at backend startup, not at DB init time). **Actually**, since Flyway runs when the backend starts, the seed SQL must run AFTER Flyway. Two options:

**Recommended approach**: Add the seed as a Flyway migration `V999__seed_local_data.sql` that is conditional on the `local` profile (activated via a `@ConditionalOnProperty` Flyway `MigrationResolver` or simply by including the seed file only in `src/main/resources/db/migration/` with a profile-conditional `FlywayConfigurationCustomizer`).

**Simpler approach** (preferred): Add a `LocalDataSeeder` Spring bean (`@Profile("local") @Component`) that uses the `TenantRepository` and `PipelineRepository` to insert seed data on `ApplicationReadyEvent` if no tenant exists yet.

**Use the simpler approach** — a `LocalDataSeeder` ApplicationListener:

```java
@Profile("local")
@Component
public class LocalDataSeeder implements ApplicationListener<ApplicationReadyEvent> {
    // Inject TenantRepository, PipelineRepository
    // On event: if tenantRepository.count() == 0, create demo tenant + pipeline
    // Demo tenant: id=00000000-0000-0000-0000-000000000001, slug=demo, name=Demo Org
    // Demo pipeline: name=Hello World Pipeline, sqlQuery="INSERT INTO output SELECT * FROM input"
    //                parallelism=1, status=DRAFT, sources=[...], sinks=[...]
}
```

Create this bean in `src/main/java/com/flinkaidlc/platform/config/LocalDataSeeder.java`.

### `Makefile`

```makefile
.PHONY: up down logs build clean

build:
	mvn clean package -DskipTests -q

up: build
	docker compose up -d --build
	@echo "Waiting for services..."
	@docker compose ps

down:
	docker compose down

logs:
	docker compose logs -f backend frontend

clean:
	docker compose down -v
	mvn clean -q
```

### Frontend nginx.conf update

The existing `nginx.conf` proxies `/api/` to `flink-platform-backend:8080`. In Docker Compose, the backend is at `backend:8090`. Update the nginx proxy target:

```
proxy_pass http://backend:8090;
```

Or parameterize via environment variable substitution in nginx using `envsubst` in the Docker entrypoint.

## Success Criteria

- [ ] `make up` completes within 3 minutes with all 7 service containers in healthy state
- [ ] `curl http://localhost:3000` returns the React frontend HTML
- [ ] `curl http://localhost:8090/actuator/health` returns `{"status":"UP"}`
- [ ] `curl -H "Authorization: Bearer dev-token" http://localhost:8090/api/v1/tenants/00000000-0000-0000-0000-000000000001` returns 200 with demo tenant data (seed data present)
- [ ] Navigating to `http://localhost:3000` in a browser shows the Flink Platform UI with the demo pipeline visible
- [ ] `make clean` tears down all containers and volumes cleanly
- [ ] Keycloak at `http://localhost:8080/realms/flink-platform` is accessible and the test user `dev@local.dev` can authenticate

## Risks

- **Keycloak startup time**: Keycloak 24 with `start-dev` can take 60-90 seconds. Mitigation: backend `depends_on: keycloak: condition: service_healthy` with 120s timeout.
- **Port collision**: Keycloak on 8080 conflicts with backend if both use default. Mitigation: backend runs on 8090 (documented in Makefile and LOCAL_DEV.md).
- **Flyway vs seed timing**: Seed data must run after Flyway migrations. Mitigation: `LocalDataSeeder` as `ApplicationReadyEvent` listener (Flyway runs in Spring context startup before this event).
- **Docker bridge SSRF**: Schema Registry container IP is in `172.*` range. Mitigation: unit-01 patches SSRF check to skip when `local` profile is active. Backend container uses `SPRING_PROFILES_ACTIVE=local`.
- **nginx proxy target**: Frontend nginx must proxy to `backend:8090` (not `flink-platform-backend:8080`). Mitigation: update `nginx.conf` or use `envsubst`.

## Boundaries

This unit does NOT:
- Set up the kind cluster or Flink Operator (unit-03)
- Write LOCAL_DEV.md (unit-04)
- Add any backend Spring profile changes (unit-01 must be merged first)
- Handle production Docker image build/publish

## Notes

- The backend Dockerfile requires `target/*.jar` to exist — `make up` runs `mvn package -DskipTests` first
- `docker-entrypoint-initdb.d/99-seed.sql` in postgres is NOT used for seed data (Flyway hasn't run at that point) — use `LocalDataSeeder` instead
- The seed tenant's `fid` can be set to `demo-fid-local`, `contactEmail` to `dev@local.dev`
- The demo pipeline should have `parallelism=1`, `checkpointIntervalMs=30000`, one Kafka source (tableName=`input`, topic=`demo-input`, bootstrapServers=`kafka:29092`, consumerGroup=`demo-cg`), one sink (tableName=`output`, topic=`demo-output`)
