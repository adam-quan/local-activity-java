package lab.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lab.api.PaymentsApi.Faults;
import lab.app.Model.Policy;
import lab.app.Model.RetrySpec;
import lab.app.Model.Step;
import lab.app.Names;
import lab.fleet.FleetConfig;
import lab.sizing.Plan;

/**
 * The scenario catalog: the guide's worked sizing example plus one scenario per row of its
 * failure-mode table. Same as the Go lab, with Java-specific settings where the SDKs differ
 * (ScheduleToStart for local activities, local retry threshold = 3 x workflow task timeout, and
 * a per-worker local activity rate limit that has to live in activity code).
 */
public final class Scenarios {
  public static final String BASELINE = "baseline", BROKEN = "broken", FIXED = "fixed";

  private Scenarios() {}

  static Faults faults(Consumer<Faults> f) {
    Faults x = new Faults();
    x.latencyMult = 1;
    f.accept(x);
    if (x.latencyMult == 0) x.latencyMult = 1;
    if (x.errorRate == 0 && !x.outage) x.errorRate = 0.05;
    return x;
  }

  /** ~5% transient 503s, as the guide assumes. */
  static Faults normal() {
    return faults(f -> {});
  }

  /** The guide's call-site configuration (worked sizing example). */
  public static Policy sizedPolicy(String name) {
    Policy p = new Policy();
    p.name = name;
    p.steps = new ArrayList<>(List.of(Step.local("authorize"), Step.local("capture")));
    p.startToCloseMs = 500;
    p.scheduleToCloseMs = 2000;
    p.retry = new RetrySpec(100, 2, 500, 4, List.of(Names.ERR_INVALID_REQUEST, Names.ERR_CIRCUIT_OPEN, Names.ERR_RETRY_AFTER_TOO_LONG));
    p.idempotency = true;
    return p;
  }

  /** The guide's per-worker configuration. */
  public static FleetConfig sizedWorker(Plan p) {
    FleetConfig c = new FleetConfig();
    c.laSlots = p.laSlotsPerWorker;
    c.laRate = p.laRatePerWorker;
    c.wftSlots = p.wftSlotsPerWorker;
    c.tuner = "fixed";
    c.stopTimeoutMs = 15_000;
    return c;
  }

  static Policy with(Policy p, Consumer<Policy> f) {
    Policy c = p.copy();
    f.accept(c);
    return c;
  }

  static FleetConfig withW(FleetConfig w, Consumer<FleetConfig> f) {
    FleetConfig c = w.copy();
    f.accept(c);
    return c;
  }

  static Stream stream(double rate, Policy p) {
    return new Stream(rate, p, 0.05, 0.05);
  }

  static Stream withQuery(Stream s) {
    s.queryRate = 0.2;
    s.historySample = 0.1;
    return s;
  }

  static Stream withHistory(Stream s, double rate) {
    s.historySample = rate;
    return s;
  }

  static Phase phase(String name, String variant, int sec, int workers, FleetConfig w, Faults f, String note, Stream... streams) {
    Phase p = new Phase();
    p.name = name;
    p.variant = variant;
    p.durationSec = sec;
    p.workers = workers;
    p.worker = w;
    p.faults = f;
    p.note = note == null ? "" : note;
    p.streams = new ArrayList<>(List.of(streams));
    return p;
  }

  static Phase act(Phase p, int atSec, String kind, String target) {
    p.actions.add(new Action(atSec, kind, target));
    return p;
  }

  static Scenario scenario(String id, String title, String guide, String symptom, String cause, String fix, String watch, Phase... phases) {
    Scenario s = new Scenario();
    s.id = id;
    s.title = title;
    s.guide = guide;
    s.symptom = symptom;
    s.cause = cause;
    s.fix = fix;
    s.watch = watch;
    s.dashboard = id.equals("sizing") ? "la-sizing" : "la-fm-" + id;
    s.phases = new ArrayList<>(List.of(phases));
    return s;
  }

  static Policy patient(String name) {
    return with(sizedPolicy(name), x -> {
      x.startToCloseMs = 10_000;
      x.scheduleToCloseMs = 30_000;
      x.retry = new RetrySpec(1000, 2, 10_000, 0, List.of());
    });
  }

  static Policy slowdownFixed() {
    return with(sizedPolicy("slowdown_fixed"), x -> {
      x.circuitBreaker = true;
      x.fallback = true;
    });
  }

  static Policy stormBroken() {
    return with(sizedPolicy("storm_broken"), x -> {
      x.startToCloseMs = 1000;
      x.scheduleToCloseMs = 10_000;
      x.retry = new RetrySpec(250, 1, 250, 0, List.of()); // no max attempts, nothing non-retryable
    });
  }

  static Policy stormFixed() {
    return with(sizedPolicy("storm_fixed"), x -> {
      x.circuitBreaker = true;
      x.fallback = true;
    });
  }

  static Policy backoffFixed() {
    return with(sizedPolicy("backoff_fixed"), x -> {
      x.maxRetryAfterMs = 1000;
      x.handOffLongWait = true;
    });
  }

  static Policy riskPolicy(String name, String mode) {
    return with(sizedPolicy(name), x -> {
      x.steps = new ArrayList<>(List.of(Step.local("authorize"), new Step("risk", mode), Step.local("capture")));
      x.startToCloseMs = 15_000;
      x.scheduleToCloseMs = 30_000;
    });
  }

  /** The receipt (email) step is not a payments call, so it gets its own headroom in slots. */
  static FleetConfig receiptWorker(FleetConfig sw) {
    return withW(sw, c -> c.laSlots = 16);
  }

  static Stream receiptStream(double rate, String name, boolean idempotent) {
    Policy p = with(sizedPolicy(name), x -> {
      x.steps = new ArrayList<>(List.of(Step.local("authorize"), Step.local("capture"), Step.local("receipt")));
      x.startToCloseMs = 3000;
      x.scheduleToCloseMs = 6000;
      x.idempotency = idempotent;
    });
    return new Stream(rate, p, 0, 0.5);
  }

  static Policy chainPolicy(String name, int checkpoint) {
    return with(sizedPolicy(name), x -> {
      x.steps = new ArrayList<>(List.of(Step.local("authorize"), Step.local("fraud"), Step.local("tax"), Step.local("capture"), Step.local("ledger")));
      x.startToCloseMs = 2000;
      x.scheduleToCloseMs = 5000;
      x.checkpointEvery = checkpoint;
    });
  }

  /** Builds every scenario for a plan. */
  public static List<Scenario> catalog(Plan p) {
    double base = p.workflowsPerSec;
    FleetConfig sw = sizedWorker(p);
    int n = p.minWorkers;
    int max = p.maxWorkers;
    List<Scenario> out = new ArrayList<>();

    out.add(scenario("sizing", "Worked sizing example", "Worked sizing example",
        "None while the fleet is at or above its floor. Below the floor the per-worker rate limiters saturate and local activities queue.",
        "Concurrency limit fixes slots per worker, the rate limit fixes the fleet floor, latency fixes the timeouts.",
        "Size slots = connections / max workers, rate = limit / max workers, keep the fleet at or above the floor.",
        "API req/s and in-flight stay under their limits at 8 and 10 workers; at 6 workers the fleet's rate cap drops below demand and local activity latency climbs.",
        phase("8 workers (sized floor)", BASELINE, 120, n, sw, normal(),
            "Guide inputs at this SCALE. Fleet at the computed minimum. Java: the per-worker rate limit is a token bucket inside the activity.",
            stream(base, sizedPolicy("sizing"))),
        phase("10 workers (autoscaler max)", BASELINE, 90, max, sw, normal(),
            "Per-worker caps were sized for 10, so the fleet cap equals the API limit exactly.", stream(base, sizedPolicy("sizing"))),
        phase("6 workers (below the floor)", BROKEN, 90, n - 2, sw, normal(),
            "Fleet rate cap = 6 x per-worker rate < demand: calls wait for tokens (inside the activity, holding a slot).",
            stream(base, sizedPolicy("sizing"))),
        phase("back to 8 workers", BASELINE, 60, n, sw, normal(), null, stream(base, sizedPolicy("sizing")))));

    out.add(scenario("api-slowdown", "API slowdown holds workflow tasks open", "Failure modes: row 1",
        "Workflow task latency and schedule-to-start rise together; queries (and signals/updates) lag.",
        "API slowdown keeps workflow tasks open; workflow task slots fill.",
        "Tight ScheduleToClose; circuit breaker in the activity; fail fast to a fallback.",
        "Workflow task execution latency, query latency, workflow task heartbeats and end-to-end latency in the broken window; breaker state and 'deferred' results in the fixed window.",
        phase("normal", BASELINE, 45, n, sw, normal(), null, withQuery(stream(base * 0.5, patient("slowdown_broken")))),
        phase("API 15x slower, patient policy", BROKEN, 120, n, sw, faults(f -> f.latencyMult = 15),
            "StartToClose 10 s, ScheduleToClose 30 s, unlimited retries, no breaker, no fallback.",
            withQuery(stream(base * 0.5, patient("slowdown_broken")))),
        phase("recover", BASELINE, 60, n, sw, normal(), null, withQuery(stream(base * 0.5, slowdownFixed()))),
        phase("API 15x slower, budgeted + breaker + fallback", FIXED, 120, n, sw, faults(f -> f.latencyMult = 15),
            "ScheduleToClose 2 s, StartToClose 500 ms, breaker opens at 50% failures, workflow returns 'deferred'.",
            withQuery(stream(base * 0.5, slowdownFixed()))),
        phase("normal", BASELINE, 45, n, sw, normal(), null, withQuery(stream(base * 0.5, slowdownFixed())))));

    out.add(scenario("slot-exhaustion", "Local activity slots exhausted", "Failure modes: row 2",
        "Local activities pile up waiting for a slot.",
        "Local activity slots sized from average latency, not Little's law at p99 and burst.",
        "Re-derive slots from Little's law; set ScheduleToStart so starvation fails visibly (Java supports it for local activities).",
        "Local activity slots available hits 0; end-to-end LA latency (schedule to completion) diverges from execution latency; with ScheduleToStart the overflow fails in 500 ms instead of waiting.",
        phase("1 slot per worker, API 3.5x slower", BROKEN, 120, n, withW(sw, c -> c.laSlots = 1), faults(f -> f.latencyMult = 3.5),
            "Little's law: ~8 calls/s/worker x ~165 ms = 1.3 concurrent > 1 slot. ScheduleToClose 30 s, no ScheduleToStart: the wait is hidden.",
            stream(base * 0.5, with(sizedPolicy("slots_broken"), x -> x.scheduleToCloseMs = 30_000))),
        phase("recover", BASELINE, 45, n, sw, normal(), null, stream(base * 0.5, slotsFixed())),
        phase("sized slots + ScheduleToStart 500 ms, API 3.5x slower", FIXED, 120, n, sw, faults(f -> f.latencyMult = 3.5),
            "Slots = API concurrency / max workers; ScheduleToClose 2 s; ScheduleToStart 500 ms.", stream(base * 0.5, slotsFixed()))));

    out.add(scenario("wft-heartbeat", "Slow call triggers workflow task heartbeats", "Failure modes: row 3",
        "History shows extra workflow task completions mid-call; queries wait ~8 s.",
        "A call passed 80% of the workflow task timeout (8 s of 10 s).",
        "Shorten the call or move it to a regular activity.",
        "Workflow task heartbeats per workflow and query latency for the risk-check flow; both drop when risk runs as a regular activity.",
        phase("10 s risk check as a local activity", BROKEN, 120, n, sw, normal(),
            "Risk vendor p50 9 s. The SDK heartbeats the workflow task at 8 s (WORKFLOW_TASK_HEARTBEAT_COEFFICIENT = 4/5).",
            stream(base * 0.5, sizedPolicy("background")), new Stream(1, riskPolicy("heartbeat_broken", "local"), 1, 1)),
        phase("recover", BASELINE, 30, n, sw, normal(), null, stream(base * 0.5, sizedPolicy("background"))),
        phase("risk check as a regular activity", FIXED, 120, n, sw, normal(),
            "The workflow task completes right after scheduling the activity; queries are answered immediately.",
            stream(base * 0.5, sizedPolicy("background")), new Stream(1, riskPolicy("heartbeat_fixed", "regular"), 1, 1))));

    out.add(scenario("pod-loss", "Pod loss: ~10 s latency spike", "Failure modes: row 4",
        "Latency spike of ~10 s after a deploy or pod loss.",
        "Worker died mid-task; the workflow waits out the workflow task timeout before another worker retries it.",
        "Graceful shutdown that drains workflow tasks (WorkerFactory.shutdown() + awaitTermination); keep the default task timeout.",
        "Max end-to-end latency jumps to 10 s+ and workflow task timeouts appear after the crash; the graceful restart shows neither.",
        phase("warm up (10 workers)", BASELINE, 45, max, receiptWorker(sw), normal(), null, receiptStream(base * 0.5, "podloss", true)),
        act(phase("kill -9 worker-a (half the fleet)", BROKEN, 75, max, receiptWorker(sw), normal(),
            "Runtime.halt(): in-flight workflow tasks on worker-a are abandoned.", receiptStream(base * 0.5, "podloss", true)), 15, "crash", "worker-a"),
        act(phase("graceful restart of worker-a", FIXED, 75, max, receiptWorker(sw), normal(),
            "SIGTERM path: WorkerFactory.shutdown() stops polling, awaitTermination(15 s) drains in-flight tasks, then exit.",
            receiptStream(base * 0.5, "podloss", true)), 15, "graceful", "worker-a")));

    out.add(scenario("duplicates", "Duplicate charges after a crash", "Failure modes: row 5",
        "Duplicate charges, emails or records.",
        "Workflow task retry re-ran completed local activities.",
        "Idempotency key per workflow ID and step; dedupe on the API side.",
        "Duplicate side effects at the API after the first crash (no key); idempotent replays instead after the second crash (key).",
        act(phase("crash without idempotency keys", BROKEN, 75, max, receiptWorker(sw), normal(), null,
            receiptStream(base * 0.5, "dupes_broken", false)), 20, "crash", "worker-a"),
        act(phase("crash with idempotency keys", FIXED, 75, max, receiptWorker(sw), normal(), null,
            receiptStream(base * 0.5, "dupes_fixed", true)), 20, "crash", "worker-a")));

    FleetConfig capsFor6 = withW(sw, c -> c.laRate = p.apiRateLimit / (n - 2));
    out.add(scenario("fleet-scale-out", "429s after the fleet scales out", "Failure modes: row 6",
        "API returns 429 only at some times of day.",
        "Fleet scaled out past the size the per-worker cap assumed.",
        "Size caps for the maximum fleet, or use a shared limiter.",
        "API rejections (rate) appear only when 10 workers run caps sized for 6; caps sized for 10 or a shared limiter keep API req/s at the limit.",
        phase("6 workers, caps sized for 6", BASELINE, 60, n - 2, capsFor6, normal(),
            "Per-worker rate = limit / 6. Fine while the fleet really is 6.", stream(base, sizedPolicy("scaleout"))),
        phase("peak: 10 workers, caps still sized for 6", BROKEN, 90, max, capsFor6, normal(),
            "Fleet cap = 10 x limit/6 = 1.67x the API limit; demand 1.8x base exceeds the API limit.", stream(base * 1.8, sizedPolicy("scaleout"))),
        phase("peak: 10 workers, caps sized for 10", FIXED, 90, max, sw, normal(),
            "Fleet cap = API limit. Excess demand waits (and some fails fast) in the workers instead of hitting the API.",
            stream(base * 1.8, sizedPolicy("scaleout"))),
        sharedLimiterPhase(p, sw, base)));

    FleetConfig unlimited = withW(sw, c -> c.laRate = 0);
    out.add(scenario("retry-storm", "Outage turns into a retry storm", "Failure modes: row 7",
        "API outage multiplies its own load.",
        "Every workflow retrying in process at short backoffs.",
        "MaximumAttempts cap, circuit breaker, non-retryable errors for client faults.",
        "API requests/s (503) during each outage window: dozens of times the normal rate when broken, near zero once the breaker opens.",
        phase("normal (2% invalid cards)", BASELINE, 45, n, unlimited, faults(f -> f.invalidRate = 0.02), null, stream(base * 0.5, stormBroken())),
        phase("outage: 250 ms constant retries, no cap", BROKEN, 75, n, unlimited, outage(),
            "Unlimited attempts every 250 ms for 10 s, invalid cards retried too, no per-worker rate cap.", stream(base * 0.5, stormBroken())),
        phase("recover", BASELINE, 45, n, sw, faults(f -> f.invalidRate = 0.02), null, stream(base * 0.5, stormFixed())),
        phase("outage: capped retries + breaker + fallback", FIXED, 75, n, sw, outage(),
            "4 attempts with backoff inside 2 s, InvalidRequest non-retryable, breaker fails fast, result 'deferred'.", stream(base * 0.5, stormFixed())),
        phase("normal", BASELINE, 45, n, sw, faults(f -> f.invalidRate = 0.02), null, stream(base * 0.5, stormFixed()))));

    Faults quota = faults(f -> {
      f.retryAfterRate = 0.5;
      f.retryAfterSec = 40;
      f.steps = List.of("capture");
    });
    out.add(scenario("long-backoff", "Retry-After above the local retry threshold", "Failure modes: row 8",
        "Many timers and workflow tasks per workflow.",
        "Backoffs or Retry-After above the local retry threshold (Java default: 3 x workflow task timeout = 30 s).",
        "Keep backoffs under the threshold; hand long waits to a regular activity.",
        "Timers and workflow tasks per workflow from sampled histories.",
        phase("429 Retry-After 40 s honoured by the local activity", BROKEN, 120, n, sw, quota,
            "40 s > the 30 s threshold: the SDK records the attempt and starts a durable timer, then a new workflow task.",
            withHistory(stream(base * 0.3, with(sizedPolicy("backoff_broken"), x -> {
              x.scheduleToCloseMs = 180_000;
              x.retry.maxAttempts = 0;
            })), 0.5)),
        phase("recover", BASELINE, 45, n, sw, normal(), null, stream(base * 0.3, backoffFixed())),
        phase("long waits handed to a regular activity", FIXED, 120, n, sw, quota,
            "Retry-After > 1 s fails the local activity fast; the step is retried by a regular activity, server-side.",
            withHistory(stream(base * 0.3, backoffFixed()), 0.5))));

    Faults slow6 = faults(f -> f.latencyMult = 6);
    out.add(scenario("long-chain", "One crash re-runs a chain of calls", "Failure modes: row 9",
        "One crash re-runs five calls.",
        "Long chain of local activities in one workflow task, no checkpoint.",
        "Put a regular activity or a durable step between groups; keep chains short.",
        "API re-executions by step after each crash: all five steps re-run without checkpoints, only the last group with them.",
        phase("warm up", BASELINE, 30, max, sw, slow6, null, stream(base * 0.3, chainPolicy("chain_broken", 0))),
        act(phase("5 local calls, no checkpoint; crash worker-a", BROKEN, 60, max, sw, slow6, null,
            stream(base * 0.3, chainPolicy("chain_broken", 0))), 15, "crash", "worker-a"),
        phase("recover", BASELINE, 30, max, sw, slow6, null, stream(base * 0.3, chainPolicy("chain_fixed", 2))),
        act(phase("checkpoint every 2 calls; crash worker-a", FIXED, 60, max, sw, slow6,
            "A 1 ms timer after every 2 local calls completes the workflow task and persists their markers.",
            stream(base * 0.3, chainPolicy("chain_fixed", 2))), 15, "crash", "worker-a")));

    Faults slow8 = faults(f -> f.latencyMult = 8);
    out.add(scenario("resource-tuner", "Resource-based tuner admits too many calls", "Failure modes: row 10",
        "Resource-based tuner admits far more concurrent calls than the API allows.",
        "I/O-bound calls don't move JVM CPU or heap, so the supplier keeps issuing slots.",
        "Fixed-size (or custom) slot supplier for local activities.",
        "Local activity slots used and API in-flight vs the concurrency limit; concurrency rejections only with the resource-based tuner.",
        phase("resource-based LA slots, API 8x slower", BROKEN, 105, n, withW(sw, c -> c.tuner = "resource"), slow8,
            "Demand needs ~47 concurrent calls; the API allows 40 (at SCALE 0.2).",
            stream(base, with(sizedPolicy("tuner_broken"), x -> x.fallback = true))),
        phase("recover", BASELINE, 45, n, sw, normal(), null, stream(base, with(sizedPolicy("tuner_fixed"), x -> x.fallback = true))),
        phase("fixed-size LA slots, API 8x slower", FIXED, 105, n, sw, slow8,
            "Slots cap the fleet below the API limit; overflow fails fast to 'deferred' in the worker.",
            stream(base, with(sizedPolicy("tuner_fixed"), x -> x.fallback = true)))));
    return out;
  }

  static Policy slotsFixed() {
    return with(sizedPolicy("slots_fixed"), x -> x.scheduleToStartMs = 500);
  }

  static Faults outage() {
    Faults f = new Faults();
    f.latencyMult = 1;
    f.outage = true;
    f.invalidRate = 0.02;
    return f;
  }

  static Phase sharedLimiterPhase(Plan p, FleetConfig sw, double base) {
    Phase ph = phase("peak: 10 workers, shared limiter", FIXED, 90, p.maxWorkers, withW(sw, c -> c.laRate = 0), normal(),
        "No per-worker cap; every call takes a token from the fleet-wide limiter.",
        stream(base * 1.8, with(sizedPolicy("scaleout"), x -> x.sharedLimiter = true)));
    ph.limiter = p.apiRateLimit;
    return ph;
  }
}
