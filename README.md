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
| POST | `/api/probe/metrics` | Probe-key-only batch telemetry ingestion (1–32 observations) |
| GET | `/api/probe/heartbeats?probeIdentifier=local-probe&limit=100` | List recent heartbeat records |
| GET, POST | `/api/resources` | List or create monitored resources |
| GET, PUT, DELETE | `/api/resources/{id}` | Retrieve, update, or delete a resource (delete returns 409 if referenced) |
| GET, POST | `/api/resources/{id}/metrics` | Query or record resource metric samples |
| GET, POST | `/api/audit-events` | List or record audit events |
| GET, POST | `/api/alerts` | List or create alert records; optional `status`, bounded `page`, and `limit` filters |
| GET | `/api/alerts/{id}` | Retrieve one alert |
| GET | `/api/alerts/{id}/history?limit=100` | Read its bounded state-transition timeline |
| PATCH | `/api/alerts/{id}/status` | Change an alert to `OPEN`, `ACKNOWLEDGED`, or `RESOLVED` (ADMIN/DEVOPS) |

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

The separate probe sends a startup heartbeat and scheduled metric batches. Its port remains 8081, while the backend remains on 8080. Probe URL, probe identity, resource identity, collection interval, and key are configurable through Spring's environment property mapping as described in the Phases 4–6 section below.

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

## Phase 3 authentication and authorization

Flyway V2 adds persistent user accounts. V1 is unchanged. Passwords are stored with BCrypt hashes; user API responses never include password data. There is no public registration endpoint. On first startup, configure `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD`; startup creates that administrator once. The bootstrap settings are ignored after an enabled ADMIN exists and never overwrite an existing account. Use a unique, randomly generated password of at least 14 characters. If no enabled administrator exists and bootstrap settings are absent or invalid, backend startup stops with an actionable error.

The backend uses server-side HTTP sessions (`JSESSIONID`, HttpOnly, 30-minute idle timeout), CSRF tokens (`XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header), and credentialed CORS restricted to `http://localhost:5173`. Login and logout are CSRF-protected too. Production deployments must use HTTPS and set `SESSION_COOKIE_SECURE=true`; configure TLS at the application or trusted reverse proxy. The frontend sends credentials and CSRF headers and keeps no password or access token in browser storage.

| Method | Endpoint | Access | Request / behavior |
|---|---|---|---|
| GET | `/api/auth/csrf` | Public | Returns `headerName` and a CSRF `token`; the CSRF cookie is also set. |
| POST | `/api/auth/login` | Public + CSRF | `{"username":"operator","password":"..."}`; creates a session and returns the current user DTO. Invalid credentials return a generic 401. |
| GET | `/api/auth/me` | Signed in | Returns `id`, `username`, `displayName`, and `role`. |
| POST | `/api/auth/logout` | Signed in + CSRF | Invalidates the session and clears the CSRF cookie. |
| GET | `/api/admin/users` | ADMIN | Lists users without password hashes. |
| POST | `/api/admin/users` | ADMIN + CSRF | `{"username":"viewer1","displayName":"Viewer One","password":"<unique-random-password-of-at-least-14-characters>","role":"VIEWER"}`. Roles: `ADMIN`, `DEVOPS`, `VIEWER`. |
| PATCH | `/api/admin/users/{id}` | ADMIN + CSRF | `{"displayName":"Updated Name"}`. |
| PATCH | `/api/admin/users/{id}/role` | ADMIN + CSRF | `{"role":"DEVOPS"}`. Administrators cannot change their own role. |
| PATCH | `/api/admin/users/{id}/enabled` | ADMIN + CSRF | `{"enabled":false}`. Administrators cannot disable themselves, and the last enabled ADMIN cannot be demoted or disabled. |

ADMIN can manage users and access the resource, metric, heartbeat history, alert, and audit APIs, including resource deletion. DEVOPS can read the same operational history except user administration, and can create/update resources, submit metrics, create/update alerts, and read audit events; DEVOPS cannot delete resources. VIEWER can read resources, metrics, heartbeat history, and alerts. VIEWER cannot read audit events or perform mutations. All routes default to deny. Existing `GET /api/health` and `GET /api/probe/heartbeat` stay public for liveness compatibility.

The probe submits only `POST /api/probe/heartbeat` and `POST /api/probe/metrics` using the shared `X-Probe-Key` header. Set the same high-entropy `PROBE_API_KEY` for the backend and probe; the backend compares it in constant time and skips CSRF only for those two POST routes when the key is valid. The key is never returned by an API. The probe cannot access heartbeat history, user routes, alerts, or other APIs. Do not expose this service key in frontend configuration. Login attempts are rate-limited per source IP in the running backend process; this in-memory limit is not shared across multiple backend replicas.

Authentication successes/failures, logout, and user provisioning/role/enabled changes are recorded in `audit_events`. These security audit writes do not include passwords, cookies, or service keys. Failed login responses do not reveal whether a username exists. MFA, external identity federation, and distributed rate limiting are not part of Phase 3.

## Phases 4–6 monitoring and alerting

The probe gathers host values using Java's `OperatingSystemMXBean`, `FileStore`, and (on Linux) `/proc/net/dev`; it never connects to PostgreSQL. A cycle posts one heartbeat and a batch of available metrics to the backend. Measurements that the host does not expose are omitted, never represented as zero. The probe uses a single Spring scheduled worker with fixed delay, a 2-second connect timeout, and a 3-second read timeout. If the backend is unavailable, the current cycle is logged as failed and the next scheduled cycle retries; there is no local queue or durable buffering. Heartbeats and metrics are attached by the configured stable resource identifier. Register that resource through the authenticated resource API before enabling probe collection.

Collected names and units:

| Metric | Unit | Source and limitations |
|---|---|---|
| `cpu.utilization` | `%` | OS CPU load fraction converted to percent; may be unavailable during initial sampling or on unsupported hosts |
| `memory.utilization` | `%` | `(total - free) / total`; values depend on the platform JVM MXBean provider |
| `memory.available` | `bytes` | OS free-memory value |
| `disk.utilization` | `%` | Usage for the file store containing the probe working directory |
| `disk.available` | `bytes` | Usable bytes on that file store |
| `network.rx.bytes`, `network.tx.bytes` | `bytes` | Cumulative counters from the first non-loopback Linux interface in `/proc/net/dev`; not rates, omitted on platforms without that interface |

Each batch observation has a UUID and UTC `collectedAt`. The backend canonicalizes metric names to lowercase dotted form and percent units to `%`, requires finite non-negative values, constrains utilization to 0–100, and accepts timestamps no older than 24 hours or more than 5 minutes in the future. A probe/resource pair must resolve to an existing resource. Observation UUIDs are unique per probe; a repeated submission is ignored and counted as a duplicate. Out-of-order values for the same resource and type are rejected. PostgreSQL resource row locking serializes evaluation for that resource; the unique active-alert key is a second duplicate guard.

Flyway V3 is forward-only and adds probe observation idempotency columns/indexes, active-alert deduplication, alert state-history storage, and a heartbeat lookup index. It leaves V1/V2 and existing records intact. It has no automatic rollback; a rollback requires a reviewed, separately planned migration and must preserve history.

Default rule behavior (application restart required after changing environment configuration):

- `CPU_ALERT_THRESHOLD` defaults to `85`; `MEMORY_ALERT_THRESHOLD` and `DISK_ALERT_THRESHOLD` default to `90` percent.
- `ALERT_CONSECUTIVE_SAMPLES` defaults to 2, and `ALERT_CORRELATION_WINDOW_SECONDS` defaults to 300. Two newest in-window utilization samples at or above threshold open one HIGH alert per resource and metric type. Further observations reuse the active OPEN/ACKNOWLEDGED incident. The first subsequent below-threshold observation resolves it. These are deterministic threshold rules, not causal inference.
- `PROBE_STALE_AFTER_SECONDS` defaults to 180; `PROBE_STALE_CHECK_MS` and `PROBE_STALE_INITIAL_DELAY_MS` default to 60000. `PROBE_STALE_MAX_RESOURCES_PER_RUN` defaults to 1000 (maximum 20000); pages rotate between runs so each scheduled pass has a bounded resource workload. A missing/stale latest heartbeat or non-HEALTHY state opens one HIGH resource alert. A healthy heartbeat resolves it.
- `LOGIN_FAILURE_ALERT_THRESHOLD` defaults to 10. The in-process security monitor records one MEDIUM `AUTH_FAILURE_BURST` indicator per source/window when repeated failed logins meet this threshold. The wording explicitly marks this as suspicious activity, not a confirmed attack. Existing login throttling remains independently enforced.
- Alert states are `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`. Transitions create `alert_status_history` and audit records. ADMIN and DEVOPS can acknowledge/resolve/reopen; VIEWER reads only. Alert creation by users is ADMIN/DEVOPS, and probe credentials have no alert API access.

Alert list returns a JSON array compatible with existing clients: `GET /api/alerts?status=OPEN&page=0&limit=20` (page is bounded to 0–10000, limit to 1–500). Detail and history are available at `/api/alerts/{id}` and `/api/alerts/{id}/history`. Audit details for engine-generated records are allowlisted and contain no service key, session identifier, CSRF value, or login credential. The dashboard counts only the currently loaded page and labels that scope; it uses actual backend data, indicates missing telemetry, shows heartbeat freshness, and exposes state actions only to ADMIN/DEVOPS. Authorization remains enforced by the backend.

## Local probe configuration

Keep the probe credential only in the backend and probe process environments. Do not place it in Vite variables. Example PowerShell setup uses a masked prompt and non-secret identifiers:

```powershell
$env:PROBE_API_KEY = Read-Host -Prompt "Probe service key" -MaskInput
$env:CLOUDSHIELD_BACKEND_URL = "http://localhost:8080"
$env:CLOUDSHIELD_PROBE_IDENTIFIER = "local-probe"
$env:CLOUDSHIELD_RESOURCE_IDENTIFIER = "host-local"
$env:CLOUDSHIELD_PROBE_COLLECTION_INTERVAL_MS = "30000"
```

Spring maps these to `cloudshield.backend.url`, `cloudshield.probe.identifier`, `cloudshield.probe.resource-identifier`, and `cloudshield.probe.collection-interval-ms`. The probe requires a nonblank API key and interval from 1 second to 1 hour. It starts collection immediately, then waits one interval between completed cycles. Its port remains 8081 for compatibility but it does not expose a public telemetry read API.

### Local setup and commands (Windows PowerShell)

1. Start the local PostgreSQL service. Create the `cloudshield` database if needed. Do not point these commands at another database.
2. Ensure `JAVA_HOME` points to a Java 21 JDK (the Maven Wrapper uses `JAVA_HOME` when it is set). Set the PostgreSQL password and initial bootstrap settings in the current PowerShell session. The password prompts are masked and are not written to disk. Configure bootstrap values only for the first startup, and set a strong one-time administrator password:

   ```powershell
   $env:DB_PASSWORD = Read-Host -Prompt "PostgreSQL password" -MaskInput
   $env:BOOTSTRAP_ADMIN_USERNAME = "cloudshield-admin"
   $env:BOOTSTRAP_ADMIN_PASSWORD = Read-Host -Prompt "Initial CloudShield admin password" -MaskInput
   $env:PROBE_API_KEY = Read-Host -Prompt "Probe service key" -MaskInput
   ```

3. Inspect/apply migrations and run the backend:

   ```powershell
   Set-Location .\backend
   .\mvnw.cmd flyway:info
   .\mvnw.cmd flyway:migrate
   .\mvnw.cmd verify
   .\mvnw.cmd spring-boot:run
   ```

   Flyway also runs automatically on application startup. The migration command only targets the `cloudshield` database URL configured in the backend Maven plugin. Keep `DB_PASSWORD` set in the same PowerShell session.

   The probe has its own test command, and the frontend can be checked separately:

   ```powershell
   Set-Location ..\probe
   .\mvnw.cmd test
   Set-Location ..\frontend
   npm run lint
   npm run build
   ```

4. In a second PowerShell window, set the probe variables above, ensure a monitored resource exists with the matching `CLOUDSHIELD_RESOURCE_IDENTIFIER`, and start the probe after the backend is running:

   ```powershell
   Set-Location .\probe
   .\mvnw.cmd spring-boot:run
   ```

5. The frontend runs at `http://localhost:5173` and calls the backend at `http://localhost:8080`. Sign in with the bootstrap admin account, then use the ADMIN user access view to provision role-limited accounts. The backend allows this frontend origin. The probe listens on `http://localhost:8081` and requires the same `PROBE_API_KEY` as the backend.

To verify stored data, use `psql` and enter the database password at its prompt:

```powershell
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT resource_identifier, name, status FROM monitored_resources;"
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT metric_type, metric_value, collected_at FROM metric_samples ORDER BY collected_at DESC LIMIT 20;"
psql -h localhost -p 5432 -U postgres -d cloudshield -c "SELECT probe_identifier, status, received_at FROM probe_heartbeats ORDER BY received_at DESC LIMIT 20;"
```

### Tests and limitations

`mvn test` runs service unit tests and PostgreSQL-backed API integration tests using Testcontainers with PostgreSQL 16. Integration tests are automatically skipped when Docker is unavailable; a successful Maven build with skipped integration tests does not verify actual migration or database persistence. Run with Docker available to exercise Flyway, Hibernate validation, endpoint behavior, and persistence against PostgreSQL.

The probe test configuration loads Mockito as a JVM startup agent for Surefire. This avoids a Windows native `javatool` attach-pipe failure observed when Mockito attempted dynamic agent loading. The test resource disables the heartbeat runner, so the context-load test does not contact the backend. Use the configured Java 21 JDK for the probe test command.

Phases 4–6 now include host collection, authenticated backend ingestion, deterministic threshold/staleness/security indicators, persisted alerts with lifecycle history, and the role-aware monitoring dashboard. Platform sensor availability, real production accuracy, scale, and causal relationships have not been benchmarked. Metric threshold processing runs in the telemetry request transaction and should be load-tested before high-volume deployment. Stale-resource checks page through resources at each configured interval. The security burst counter is in-memory and resets on restart or across replicas; no distributed correlation or external event broker is used.
