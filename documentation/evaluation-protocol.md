# CloudShield Reproducible Evaluation Protocol

## Purpose and reporting rule

This protocol defines experiments for “CloudShield: Probe-Based Monitoring and Identity-Aware Access Management for Cloud-Native Infrastructure.” It is a procedure, not a result. No performance, detection, or security measurements are asserted here. Record raw observations and configuration for each run; do not fill missing results with estimates. Report automated tests, mocked tests, local API tests, and PostgreSQL-backed tests separately.

## 1. Freeze and record the environment

Before each experiment, record:

- CloudShield commit hash and dirty/clean worktree state.
- OS edition/build, CPU model/core count, installed RAM, storage device, and whether the machine is physical or virtual.
- Java vendor/version, Maven wrapper version, Node/npm versions, PostgreSQL version, frontend browser/version, and Docker version/status if applicable.
- Backend JVM heap settings, PostgreSQL configuration, probe collection interval, configured alert thresholds, stale interval, frontend/API URLs, and any CPU/memory limits.
- Start/end UTC timestamps, warm-up duration, measurement duration, number of repetitions, and sample counts.
- Whether probe and backend are on the same machine or separate hosts. Keep test data and DB statistics scoped to the `cloudshield` database.

Do not include passwords, API keys, MFA secrets, cookies, OTP values, or unredacted session/network identifiers in experiment artifacts.

Suggested version capture (run in the project root):

```powershell
git rev-parse HEAD
git status --short
java -version
Set-Location .\backend; .\mvnw.cmd -version
Set-Location ..\probe; .\mvnw.cmd -version
Set-Location ..\frontend; node --version; npm --version
```

Capture PostgreSQL version and database size using read-only queries after connecting specifically to `cloudshield`:

```sql
SELECT version();
SELECT pg_database_size('cloudshield') AS cloudshield_bytes;
```

## 2. Monitoring overhead and ingestion capacity

### 2.1 Probe and backend resource overhead

For each collection interval (recommend 1, 5, 30, and 60 seconds, subject to the probe's configured minimum), run a no-probe baseline and then a probe-enabled run. Keep the machine workload and backend configuration constant. Warm up for five minutes, measure for at least 30 minutes, and repeat each condition five times where practical. If the host cannot support those durations, report the actual values and do not compare unequal durations as if they were equivalent.

Record process CPU time or sampled CPU percentage, working set/RSS, private/committed memory, process uptime, JVM heap used/committed, and backend request counts at a fixed sampling cadence (for example, one second). On Windows, record `Get-Process` samples for the Java process; on Linux, record `/proc/<pid>/stat`, `/proc/<pid>/status`, and a tool such as `pidstat`. Keep the collection method and sampling cadence identical between baseline and probe runs. Report medians and p95 values per run, plus the absolute and percentage change relative to that run's baseline. Keep probe and backend process measurements separate.

### 2.2 Sample growth and ingestion throughput

For a fixed duration and collection interval, record:

- Probe cycles attempted, successful heartbeats, metric observations submitted, and API failures.
- Persisted metric/heartbeat row-count deltas before and after the run.
- `pg_database_size('cloudshield')` before and after, and table/index sizes for `metric_samples` and `probe_heartbeats`.
- Successful observations per second, request latency median/p95/p99, and error/duplicate rates. Exclude warm-up and report the excluded interval.

Use a dedicated, non-production resource registered for the test. Do not delete existing records or reset the database. Isolate each run by a unique resource/probe identifier and observation UUID namespace. If cleanup is necessary, use a separately reviewed retention policy or a disposable database; do not use destructive cleanup against a shared `cloudshield` database.

## 3. Alert detection and lifecycle

Use controlled observations against a dedicated test resource. Record the threshold, consecutive-sample count, correlation window, probe stale threshold, stale scheduler interval, and each source observation timestamp. Use synchronized UTC clocks and preserve the raw request/response timestamps with credentials redacted.

Define and measure:

- **Threshold alert delay:** time from receipt of the final qualifying above-threshold observation to the first successful read showing its active alert.
- **Stale detection delay:** time from the expected stale boundary (`last healthy heartbeat + configured stale threshold`) to first read showing the stale alert. Scheduler cadence contributes additional delay; report the configured cadence and observed value.
- **Deduplication:** number of active matching alerts after repeated qualifying observations in one correlation window. Report whether exactly one active record and expected history transitions are observed.
- **Recovery delay:** time from receipt of the first qualifying recovery sample/heartbeat to the first read showing the corresponding alert resolved.
- **False positive (scenario-scoped):** an alert for which the experiment's predeclared ground-truth scenario says no rule should fire.
- **Missed detection (scenario-scoped):** a predeclared qualifying threshold/stale scenario for which no expected alert appears within the predeclared deadline.

Run each controlled scenario at least five times if practical: below-threshold baseline, single high sample, required consecutive high samples, continued high samples (dedup), recovery, missing heartbeat, and unhealthy heartbeat. Report each trial; do not combine user-created alerts with engine-generated alerts in detection statistics. State whether the observations were synthetic API test data or real host telemetry.

## 4. Security evaluation matrix

For each automated/API scenario, record endpoint, role/state, expected result, actual status/result, test type (unit, MockMvc, live HTTP, or PostgreSQL-backed), and test count. Cover:

| Area | Allow case | Deny/failure case |
|---|---|---|
| Authentication | Valid password; valid TOTP challenge when enabled | Invalid password; MFA enabled without challenge; invalid/replayed/expired TOTP |
| MFA lifecycle | Authenticated enrollment, verified enablement, password + TOTP disable | Missing/invalid encryption key; invalid code; repeated code; unauthorized setup |
| Session | Current-user lookup; session ID change on login; logout invalidation | Reuse invalidated session; wrong role after role update |
| CSRF | Valid CSRF on session mutations | Missing/invalid CSRF on login, MFA, user/admin and operational mutations |
| RBAC | ADMIN user administration; DEVOPS permitted operational writes; VIEWER reads | DEVOPS/VIEWER admin operations; VIEWER writes; unauthenticated protected access |
| Probe key | Heartbeat and metric ingestion with valid key | Invalid key and any read/user/alert/admin route with probe key |
| Throttling | Attempts under configured limit | Login and MFA failures beyond configured in-process limit |
| Data exposure | Responses contain only public DTO fields | Search response bodies, audit detail, and logs for password hashes, OTPs, keys, provisioning URIs, cookies, and tokens |

Report the exact number of tests executed, failed, errored, and skipped. A skipped Testcontainers test is not a pass. A mocked authentication or database test is not a live security or persistence result. Conduct at least one negative test per route family and each role when running a live test campaign.

## 5. End-to-end integration scenario

Use a configured local installation and the intended `cloudshield` database. First capture Flyway state and schema without modifying data. Register a dedicated test resource, then start backend, frontend, and probe using separate terminal sessions with process-only secret environment variables. Enroll MFA only on a designated test account; do not disable or replace the existing administrator's account. Confirm probe observations and heartbeats arrive, inspect metrics/alerts/history/audit using read-only SQL/API calls, exercise deduplication, acknowledgement, recovery, CSRF rejection, role denial, and logout invalidation, then record whether the dashboard shows the same persisted records.

Capture request times, returned HTTP statuses, non-sensitive record counts, migration versions, and test account role only. Do not record usernames, secret values, cookie values, source IPs, or raw request headers in paper artifacts. Do not delete records from a shared database after a run.

If database, Docker, credentials, frontend browser, or a service is unavailable, mark that scenario **BLOCKED** or **NOT TESTED**, give the blocker, and retain successful unit/mock results as separate evidence. Never infer database persistence from a successful compile or skipped integration test.

## 6. Results template

Copy this table for each repeated run and attach raw, redacted measurements:

| Experiment/run ID | Commit | Environment | Interval/configuration | Duration | Samples | Median / p95 | Errors/skips | Notes |
|---|---|---|---|---|---:|---|---|---|
| _not measured_ | | | | | | | | |

For detection results, report each trial's expected event time, observed alert time, deduplication count, resolution time, ground truth, and outcome. For security, report an allow/deny matrix and exact test counts. Include deviations, clock synchronization, missing data, test exclusions, and limitations. Do not call the work conference-paper-ready until the evaluation has been executed and independently reproducible from the recorded environment and procedure.
