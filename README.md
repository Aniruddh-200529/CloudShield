# CloudShield

CloudShield is a research project for probe-based cloud infrastructure monitoring and identity-aware access management. This repository contains the React/Vite frontend, Spring Boot backend, and a separate Spring Boot probe.

## Project layout

- `backend/` — Java 21 / Spring Boot REST API and PostgreSQL persistence
- `frontend/` — React and Vite dashboard
- `probe/` — separate monitoring probe application (port 8081)
- `documentation/` — project material

## Phase 2 backend

The backend keeps the Phase 1 `GET /api/health` response and `GET /api/probe/heartbeat` response. It adds DTO-based REST endpoints for monitored resources, metric samples, persisted probe heartbeats, audit events, and alert records. Domain logic is in services, with Spring Data repositories and JPA entities behind the API.

Flyway applies versioned SQL migrations from `backend/src/main/resources/db/migration` at application startup. Hibernate uses `ddl-auto=validate`; it checks the migrated schema and does not create or delete tables. The configured database is `cloudshield` on local PostgreSQL port 5432. The password is read from `DB_PASSWORD` and is never stored in this repository.

### Database schema

- `monitored_resources` — unique external resource identifier, identity, type, address, environment, status, and creation/update timestamps.
- `metric_samples` — typed numeric samples, each linked to a resource with a collection timestamp; indexes support per-resource time-range reads.
- `probe_heartbeats` — probe identity, optional resource link, health status/message, and receipt timestamp.
- `audit_events` — event, optional actor/target/resource, outcome, timestamp, and optional JSON details stored as text.
- `alert_records` — alert type, severity, description, optional resource, status, and acknowledgement/resolution timestamps.

Resource deletion is restricted by foreign keys while dependent records exist. Metric, heartbeat, audit, and alert history is retained. All timestamps use PostgreSQL `TIMESTAMPTZ` and Java `Instant` (UTC).

### API

Resource and history list results are bounded. Resource listing supports `page` (default 0) and `size` (default 100, maximum 200). Metric history supports optional ISO-8601 `from` and `to` values and `limit` (default 100, maximum 500). Other history endpoints accept a `limit` (default 100, maximum 500).

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/health` | Existing Phase 1 application health text response |
| GET, POST | `/api/probe/heartbeat` | Existing compatibility response (GET); persist a probe heartbeat (POST) |
| GET | `/api/probe/heartbeats?probeIdentifier=local-probe&limit=100` | List recent heartbeat records |
| GET, POST | `/api/resources` | List or create monitored resources |
| GET, PUT, DELETE | `/api/resources/{id}` | Retrieve, update, or delete a resource (delete returns 409 if referenced) |
| GET, POST | `/api/resources/{id}/metrics` | Query or record resource metric samples |
| GET, POST | `/api/audit-events` | List or record audit events |
| GET, POST | `/api/alerts` | List or create alert records; optional `status` filter |
| PATCH | `/api/alerts/{id}/status` | Change an alert to `OPEN`, `ACKNOWLEDGED`, or `RESOLVED` |

Resource updates use `PUT` with the full resource request; `resourceIdentifier` is immutable after creation and must match the current identifier.

Create a resource:

```http
POST /api/resources
Content-Type: application/json

{
  "resourceIdentifier": "vm-001",
  "name": "Research VM",
  "resourceType": "VM",
  "address": "10.0.0.10",
  "environment": "research",
  "status": "ACTIVE"
}
```

The API responds with `201 Created` and a resource DTO containing its generated `id` and timestamps. Record a metric using that ID:

```http
POST /api/resources/{id}/metrics
Content-Type: application/json

{"metricType":"cpu.utilization","value":42.5,"unit":"percent"}
```

The sample collection time defaults to the current time. Query a bounded time range with `GET /api/resources/{id}/metrics?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&limit=100`.

Record a probe heartbeat with `POST /api/probe/heartbeat`:

```json
{"probeIdentifier":"local-probe","status":"HEALTHY","healthMessage":"Probe is running"}
```

The separate probe sends this POST on startup. Its port remains 8081, while the backend remains on 8080. Set optional probe variables `CLOUDSHIELD_BACKEND_URL` (default `http://localhost:8080`) and `CLOUDSHIELD_PROBE_IDENTIFIER` (default `local-probe`) through Spring's environment property mapping if needed.

Record and retrieve an audit event with `POST /api/audit-events` and `GET /api/audit-events?limit=100`. `eventType` and `outcome` are required; the actor, target, resource, timestamp, and structured details are optional:

```json
{
  "eventType": "RESOURCE_CHECK",
  "actorIdentifier": "operator-1",
  "targetIdentifier": "vm-001",
  "occurredAt": "2026-10-09T10:00:00Z",
  "outcome": "SUCCESS",
  "details": {"source": "manual-check"}
}
```

Create and list alerts with `POST /api/alerts` and `GET /api/alerts?status=OPEN&limit=100`. `alertType`, `severity` (`INFO`, `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`), and `description` are required; `resourceId` is optional:

```json
{"alertType":"CAPACITY","severity":"HIGH","description":"Capacity threshold exceeded"}
```

Update the returned alert ID with `PATCH /api/alerts/{id}/status` and a body such as `{"status":"ACKNOWLEDGED"}`. Valid states are `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`. A resolved alert must be reopened before it can be acknowledged again; status changes set the corresponding lifecycle timestamps.

Errors use a consistent JSON shape with `timestamp`, `status`, `error`, `message`, `path`, and `validationErrors`. Validation errors are `400`, missing records are `404`, duplicate identifiers and data/foreign-key conflicts are `409`. Unexpected server errors return a generic message without SQL details, stack traces, or credentials.

### Local setup and commands (Windows PowerShell)

1. Start the local PostgreSQL service. Create the `cloudshield` database if needed. Do not point these commands at another database.
2. Ensure `JAVA_HOME` points to a Java 21 JDK (the Maven Wrapper uses `JAVA_HOME` when it is set). Set the password for the configured `postgres` database user in the current PowerShell session. The prompt masks input and the value is not written to disk:

   ```powershell
   $env:DB_PASSWORD = Read-Host -Prompt "PostgreSQL password" -MaskInput
   ```

3. Inspect/apply migrations and run the backend:

   ```powershell
   Set-Location .\backend
   .\mvnw.cmd flyway:info
   .\mvnw.cmd flyway:migrate
   .\mvnw.cmd test
   .\mvnw.cmd spring-boot:run
   ```

   Flyway also runs automatically on application startup. The migration command only targets the `cloudshield` database URL configured in the backend Maven plugin. Keep `DB_PASSWORD` set in the same PowerShell session.

4. In a second PowerShell window, start the probe after the backend is running:

   ```powershell
   Set-Location .\probe
   .\mvnw.cmd spring-boot:run
   ```

5. The frontend runs at `http://localhost:5173` and calls the backend at `http://localhost:8080`. The backend allows the existing frontend origin. The probe listens on `http://localhost:8081`.

To verify stored data, use `psql` and enter the database password at its prompt:

```powershell
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT resource_identifier, name, status FROM monitored_resources;"
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT metric_type, metric_value, collected_at FROM metric_samples ORDER BY collected_at DESC LIMIT 20;"
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT probe_identifier, status, received_at FROM probe_heartbeats ORDER BY received_at DESC LIMIT 20;"
```

### Tests and limitations

`mvn test` runs service unit tests and PostgreSQL-backed API integration tests using Testcontainers with PostgreSQL 16. Integration tests are automatically skipped when Docker is unavailable; a successful Maven build with skipped integration tests does not verify actual migration or database persistence. Run with Docker available to exercise Flyway, Hibernate validation, endpoint behavior, and persistence against PostgreSQL.

These Phase 2 APIs are temporarily unauthenticated for local development. Authentication/authorization, role enforcement, MFA, metric collectors, automated event correlation, alert evaluation/background processing, and dashboard redesign are deferred. The database tables provide foundations only; those later features are not implemented by their existence.
