# CLAUDE_HANDOFF.md — Local Activity Lab, Java SDK edition

A port of the Go "Local Activity Lab" (`lademo/`) to the Temporal **Java SDK 1.40.0**. It runs on the **Temporal CLI 1.10.0** dev server (Server 1.32.2). Architecture, ports, JSON wire formats (snake_case), scenario ids, metric names and dashboards are the same as in the Go lab, so the control page (`ui.html`) is shared.

## 1. Status

- **Run end to end on 2026-11-07** (Docker Desktop, macOS, `SCALE=0.2`). All 20 JUnit tests pass on the real SDK 1.40.0, including `CheckoutWorkflowTest`. All 8 services start; all Prometheus targets are up; 13 dashboards are provisioned; all 200 dashboard queries execute against live Prometheus.
- All 11 scenarios were run; each shows the symptom in the README's "Watch" column in its broken phase, and the fix in its fixed phase.
- No local Gradle needed: `docker run --rm -v "$PWD":/src -v lab-gradle-cache:/home/gradle/.gradle -w /src gradle:8.14.3-jdk21 gradle test installDist --no-daemon --project-cache-dir /tmp/pc`.
- Fixed during the first run: (1) workflow task pollers are now fixed (`PollerBehaviorSimpleMaximum(5)`); autoscaling skewed load onto a few workers, which hit their 20/s token cap and failed 8-13% of baseline workflows on ScheduleToClose. (2) `Load.count` now detects Java SDK heartbeats, whose completion records local activity markers before the forced `WorkflowTaskScheduled`.
- On a laptop, keep the Mac awake (`caffeinate -i`) during runs: idle sleep freezes the Docker VM and shows up as gaps and fake throughput drops.

## 2. Key decisions (Java-specific)

| Decision | Why |
|---|---|
| Per-worker local activity rate cap = `TokenBucket` (burst 1) inside the activity | The Java SDK has no local activity rate option. The wait holds a slot and spends StartToClose, so it is observed as `demo_la_rate_wait_seconds`. If the token is not granted within half the attempt budget, the activity fails with `LimiterTimeout` and `nextRetryDelay` 50 ms. |
| Custom tally `StatsReporter` (`lab.fleet.PromStatsReporter`) into the lab's own `prom.Registry` | Gives Go-identical names (timers become `_seconds` histograms, counters get `_total`) without Micrometer, so the dashboards are shared. |
| One `WorkflowServiceStubs` + `WorkflowClient` + `WorkerFactory` per virtual worker | Each virtual worker gets its own identity, slots, sticky queue and metrics `worker_id`, like separate pods. |
| Virtual threads (`setUsingVirtualThreads`, `setUsingVirtualWorkflowThreads`), JDK `HttpServer`/`HttpClient` | Cheap concurrency; no framework dependencies. |
| HTTP connection pool modeled as a fair `Semaphore` | Pool waits count against StartToClose; the client-vs-server latency gap shows them. |
| Slot-exhaustion fix adds `ScheduleToStart` 500 ms | Java supports it for local activities (Go does not). |
| Long-backoff uses `Retry-After` 40 s; the broken variant's ScheduleToClose is 180 s | Java's local retry threshold is 30 s (3 × the 10 s workflow task timeout). |
| Resource tuner: `CompositeTuner(Fixed wft, Fixed 1, ResourceBasedSlotSupplier.createForLocalActivity(controller 0.9/0.9, min 1, max 10000, ramp 50 ms), Fixed 1)` | Reproduces "resource tuner admits too many I/O-bound calls". |
| Crash = `Runtime.halt(137)`; graceful = `WorkerFactory.shutdown()` + `awaitTermination(15 s)` + `System.exit(0)`. Docker restarts both. | kill -9 vs drained stop. |

## 3. Java SDK v1.40.0 facts (from source)

- `localRetryThreshold` default = workflow task timeout × 3; per-call `LocalActivityOptions.setLocalRetryThreshold`.
- The workflow task heartbeat fires at 4/5 of the task timeout.
- `ApplicationFailure.nextRetryDelay` is honoured by local activity retries.
- Defaults: 200 slots for workflow tasks, activities and local activities; 5 pollers.
- `worker_type` tag values: `WorkflowWorker`, `ActivityWorker`, `LocalActivityWorker`.
- Dependencies: tally-core 0.13.0, slf4j-api 1.7.36, Jackson 2.15.x, gRPC 1.76.0.

## 4. Layout and contracts

The layout is in the README. Contracts are the same as the Go lab's handoff (`lademo/CLAUDE_HANDOFF.md` §6): environment variables, the `/admin/faults` and `/admin/limits` JSON, limiter `/acquire`, worker control `/config` `/crash` `/shutdown`, and runner `/api/info` `/api/state` `/api/run` `/api/stop` `/api/pause`. Java-only policy fields: `schedule_to_start_ms` and `local_retry_threshold_ms`.

## 5. Known risks

- `Fleet.java` is the most version-sensitive file (tuner, poller behavior and `WorkerFactoryOptions` builders). If the real SDK rejects a signature, fix it there first.
- Eight virtual workers per JVM, each with its own gRPC channel, plus 600-entry caches: watch heap at `SCALE=1`.
- `JVMSystemResourceInfo` reports process CPU and JVM heap. In a container the controller sees cgroup-limited values only if the JVM's container support is on (it is by default).

- Observed, not fixed: `temporal_worker_task_slots_available{worker_type="LocalActivityWorker"}` goes briefly negative (down to -5) when many local activities hit StartToClose timeouts (API slowdown fixed, resource tuner fixed). The value comes straight from the SDK.
- Observed, not fixed: the mock API checks the idempotency store on arrival but writes it on completion, so a retry that arrives while the first attempt is still running on the server is a duplicate even with a key (seen in API slowdown and resource tuner, not in the Duplicates scenario).
- Observed, by design: in *Fleet scale-out* and *Resource tuner*, the fixed phase removes the 429s but completes fewer workflows than the broken phase, because demand exceeds the API's capacity (about 227 calls/s against 200 req/s; 8× slower API against 4 slots per worker).

## 6. Next steps

1. Take dashboard screenshots for the README.
2. Decide whether the two fixed phases above should lower demand so they also complete more workflows.
3. Optional: a Micrometer variant of the metrics (`MicrometerClientStatsReporter`) for teams already on Micrometer.
