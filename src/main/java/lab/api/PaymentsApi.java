package lab.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import lab.Json;
import lab.http.Http;
import lab.prom.Registry;
import lab.sizing.Plan;

/**
 * Mock payments API with the guide's limits: a request-rate limit (token bucket), a
 * concurrent-request limit, a lognormal latency profile (p50 40 ms, p99 150 ms), idempotency
 * keys, and injectable faults. Every side effect is recorded per (order, step), so re-executed
 * calls and duplicate side effects show up as metrics.
 */
public final class PaymentsApi {

  /** Latency profile of one endpoint. */
  public record Profile(double p50, double p99, boolean limited, String service) {}

  public static final Map<String, Profile> PROFILES =
      Map.of(
          "authorize", new Profile(0.040, 0.150, true, "payments"),
          "capture", new Profile(0.040, 0.150, true, "payments"),
          "fraud", new Profile(0.040, 0.150, true, "payments"),
          "tax", new Profile(0.040, 0.150, true, "payments"),
          "ledger", new Profile(0.040, 0.150, true, "payments"),
          "receipt", new Profile(0.8, 1.8, false, "email"),
          "risk", new Profile(9, 11, false, "risk-vendor"));

  /** Injected faults (snake_case JSON, same as the Go version). */
  public static final class Faults {
    public double latencyMult = 1;
    public double errorRate;
    public boolean outage;
    public double invalidRate;
    public double retryAfterRate;
    public int retryAfterSec;
    public List<String> steps = List.of();

    public Faults() {}

    public Faults copy() {
      Faults f = new Faults();
      f.latencyMult = latencyMult;
      f.errorRate = errorRate;
      f.outage = outage;
      f.invalidRate = invalidRate;
      f.retryAfterRate = retryAfterRate;
      f.retryAfterSec = retryAfterSec;
      f.steps = steps == null ? List.of() : List.copyOf(steps);
      return f;
    }
  }

  /** Payments API limits. */
  public static final class Limits {
    public double ratePerSec;
    public double burst;
    public int concurrency;

    public Limits() {}
  }

  private record Exec(int count, long last) {}

  private record Stored(byte[] body, long at) {}

  private volatile Faults faults = new Faults();
  private volatile Limits limits = new Limits();
  private double tokens;
  private long lastNanos = System.nanoTime();
  private final AtomicLong inflight = new AtomicLong();
  private final AtomicLong inflightPeak = new AtomicLong();
  private final Map<String, Exec> execs = new ConcurrentHashMap<>();
  private final Map<String, Stored> idem = new ConcurrentHashMap<>();

  private final Registry reg = new Registry();
  private final Registry.Counter requests = reg.counter("api_requests_total", "Requests by endpoint and HTTP status.", "service", "step", "code");
  private final Registry.Counter rejections = reg.counter("api_rejections_total", "Requests rejected by the API's own limits (429).", "step", "reason");
  private final Registry.Histogram duration = reg.histogram("api_request_duration_seconds", "Server-side request duration.", Registry.LATENCY_BUCKETS, "service", "step");
  private final Registry.Counter duplicates = reg.counter("api_duplicate_side_effects_total",
      "Side effects performed again for an order+step that already succeeded WITHOUT a matching idempotency key.", "step");
  private final Registry.Counter replays = reg.counter("api_idempotent_replays_total", "Requests answered from the idempotency store.", "step");
  private final Registry.Counter reexecutions = reg.counter("api_reexecutions_total",
      "Calls for an order+step that had already succeeded (replayed or duplicated).", "step");
  private final Registry.Counter sideEffects = reg.counter("api_side_effects_total", "Side effects performed.", "step");
  private final Registry.Gauge gInflight = reg.gauge("api_inflight", "Requests in flight at scrape time.", "service");
  private final Registry.Gauge gPeak = reg.gauge("api_inflight_peak", "Peak concurrent payments requests since the previous scrape.", "service");
  private final Registry.Gauge gLimit = reg.gauge("api_limit", "Configured payments API limits.", "limit");
  private final Registry.Gauge gFault = reg.gauge("api_fault", "Active fault injection settings.", "fault");

  public PaymentsApi(Plan plan) {
    Limits l = new Limits();
    l.ratePerSec = plan.apiRateLimit;
    l.burst = Math.max(1, plan.apiRateLimit * 0.1);
    l.concurrency = plan.apiConcurrency;
    limits = l;
    tokens = l.burst;
    Faults f = new Faults();
    faults = f;
    publishConfig();
    Thread.ofVirtual().start(this::sweep);
  }

  private void publishConfig() {
    Limits l = limits;
    Faults f = faults;
    gLimit.set(l.ratePerSec, "rate_per_sec");
    gLimit.set(l.concurrency, "concurrency");
    gLimit.set(l.burst, "burst");
    gFault.set(f.latencyMult, "latency_mult");
    gFault.set(f.errorRate, "error_rate");
    gFault.set(f.outage ? 1 : 0, "outage");
    gFault.set(f.invalidRate, "invalid_rate");
    gFault.set(f.retryAfterRate, "retry_after_rate");
    gFault.set(f.retryAfterSec, "retry_after_sec");
  }

  private void sweep() {
    while (true) {
      try {
        Thread.sleep(30_000);
      } catch (InterruptedException e) {
        return;
      }
      long cut = System.currentTimeMillis() - 600_000;
      execs.entrySet().removeIf(e -> e.getValue().last() < cut);
      idem.entrySet().removeIf(e -> e.getValue().at() < cut);
    }
  }

  /** Token bucket: returns 0 when a token was taken, else nanoseconds until one is available. */
  private synchronized long takeToken() {
    Limits l = limits;
    if (l.ratePerSec <= 0) return 0;
    long now = System.nanoTime();
    tokens = Math.min(l.burst, tokens + (now - lastNanos) / 1e9 * l.ratePerSec);
    lastNanos = now;
    if (tokens >= 1) {
      tokens -= 1;
      return 0;
    }
    return (long) ((1 - tokens) / l.ratePerSec * 1e9);
  }

  static double sampleLatency(Profile p, double mult) {
    double mu = Math.log(p.p50());
    double sigma = Math.log(p.p99() / p.p50()) / 2.3263;
    return Math.exp(mu + sigma * ThreadLocalRandom.current().nextGaussian()) * mult;
  }

  static double hashUnit(String s) {
    // FNV-1a 64-bit, same as the Go version, so "invalid card" orders are stable.
    long h = 0xcbf29ce484222325L;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= 0x100000001b3L;
    }
    return Long.remainderUnsigned(h, 1_000_000) / 1_000_000.0;
  }

  private boolean faulted(Faults f, String step) {
    if (f.steps == null || f.steps.isEmpty()) return PROFILES.get(step).limited();
    return f.steps.contains(step);
  }

  /** Request body. */
  public static final class Body {
    public String orderId;
    public int amountCents;

    public Body() {}
  }

  private static void sleepSeconds(double s) {
    try {
      Thread.sleep((long) (s * 1000), (int) ((s * 1e9) % 1_000_000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void reply(HttpExchange ex, Profile p, String step, int code, long start, Map<String, String> hdr, Object body) throws IOException {
    if (hdr != null) hdr.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
    Http.json(ex, code, body);
    requests.inc(p.service(), step, Integer.toString(code));
    duration.observe((System.nanoTime() - start) / 1e9, p.service(), step);
  }

  private void handleCall(HttpExchange ex) throws IOException {
    long start = System.nanoTime();
    String step = ex.getRequestURI().getPath().substring("/v1/".length());
    Profile prof = PROFILES.get(step);
    if (prof == null) {
      Http.text(ex, 404, "unknown endpoint");
      return;
    }
    Body body;
    try {
      body = Json.read(ex.getRequestBody(), Body.class);
    } catch (IOException e) {
      body = null;
    }
    if (body == null || body.orderId == null || body.orderId.isEmpty()) {
      reply(ex, prof, step, 400, start, null, Map.of("error", "bad body"));
      return;
    }
    String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
    Faults f = faults;
    boolean isFaulted = faulted(f, step);

    // 1. Idempotent replay: answered from the store, no side effect.
    if (key != null && !key.isEmpty()) {
      Stored st = idem.get(key);
      if (st != null) {
        sleepSeconds(0.003);
        replays.inc(step);
        reexecutions.inc(step);
        ex.getResponseHeaders().set("Idempotent-Replayed", "true");
        Http.send(ex, 200, "application/json", st.body());
        requests.inc(prof.service(), step, "200");
        duration.observe((System.nanoTime() - start) / 1e9, prof.service(), step);
        return;
      }
    }

    // 2. The API's own limits.
    if (prof.limited()) {
      long wait = takeToken();
      if (wait > 0) {
        long ms = Math.max(50, wait / 1_000_000);
        rejections.inc(step, "rate");
        reply(ex, prof, step, 429, start,
            Map.of("Retry-After", "1", "X-Retry-After-Ms", Long.toString(ms), "X-Reject-Reason", "rate"),
            Map.of("error", "rate limit exceeded"));
        return;
      }
      int conc = limits.concurrency;
      if (conc > 0 && inflight.get() >= conc) {
        rejections.inc(step, "concurrency");
        reply(ex, prof, step, 429, start,
            Map.of("Retry-After", "1", "X-Retry-After-Ms", "100", "X-Reject-Reason", "concurrency"),
            Map.of("error", "too many concurrent requests"));
        return;
      }
    }

    // 3. Injected faults.
    if (isFaulted) {
      if (f.outage) {
        sleepSeconds(0.002);
        reply(ex, prof, step, 503, start, null, Map.of("error", "outage"));
        return;
      }
      if (f.invalidRate > 0 && hashUnit(body.orderId) < f.invalidRate) {
        sleepSeconds(0.005);
        reply(ex, prof, step, 400, start, null, Map.of("error", "card declined: invalid card"));
        return;
      }
      if (f.retryAfterRate > 0 && ThreadLocalRandom.current().nextDouble() < f.retryAfterRate) {
        rejections.inc(step, "quota");
        reply(ex, prof, step, 429, start,
            Map.of("Retry-After", Integer.toString(f.retryAfterSec), "X-Reject-Reason", "quota"),
            Map.of("error", "quota cooldown"));
        return;
      }
    }

    // 4. Do the work. Like a real API, the server finishes (and commits the side effect) even if
    //    the client has already timed out and gone away.
    if (prof.limited()) {
      long n = inflight.incrementAndGet();
      inflightPeak.accumulateAndGet(n, Math::max);
    }
    double mult = isFaulted && f.latencyMult > 0 ? f.latencyMult : 1;
    try {
      sleepSeconds(sampleLatency(prof, mult));
    } finally {
      if (prof.limited()) inflight.decrementAndGet();
    }

    if (isFaulted && f.errorRate > 0 && ThreadLocalRandom.current().nextDouble() < f.errorRate) {
      reply(ex, prof, step, 503, start, null, Map.of("error", "transient failure"));
      return;
    }

    Map<String, String> resp = Map.of("order_id", body.orderId, "step", step, "id", step + "-" + body.orderId + "-" + System.nanoTime());
    byte[] raw = Json.write(resp);
    String execKey = body.orderId + "|" + step;
    long now = System.currentTimeMillis();
    Exec rec = execs.merge(execKey, new Exec(1, now), (a, b) -> new Exec(a.count() + 1, now));
    if (key != null && !key.isEmpty()) idem.put(key, new Stored(raw, now));
    sideEffects.inc(step);
    if (rec.count() > 1) {
      reexecutions.inc(step);
      duplicates.inc(step);
    }
    Http.send(ex, 200, "application/json", raw);
    requests.inc(prof.service(), step, "200");
    duration.observe((System.nanoTime() - start) / 1e9, prof.service(), step);
  }

  private void handleFaults(HttpExchange ex) throws IOException {
    if ("POST".equals(ex.getRequestMethod())) {
      Faults f = Json.read(ex.getRequestBody(), Faults.class);
      if (f.latencyMult <= 0) f.latencyMult = 1;
      if (f.steps == null) f.steps = List.of();
      faults = f;
      publishConfig();
      System.err.println("faults set: " + Json.string(f));
    }
    Http.json(ex, 200, faults);
  }

  private void handleLimits(HttpExchange ex) throws IOException {
    if ("POST".equals(ex.getRequestMethod())) {
      Limits l = Json.read(ex.getRequestBody(), Limits.class);
      if (l.burst <= 0) l.burst = Math.max(1, l.ratePerSec * 0.1);
      synchronized (this) {
        limits = l;
        tokens = Math.min(tokens, l.burst);
      }
      publishConfig();
    }
    Http.json(ex, 200, limits);
  }

  /** All routes: /v1/*, /admin/faults, /admin/limits, /healthz, /metrics. */
  public Map<String, HttpHandler> routes() {
    return Map.of(
        "/v1/", Http.safe(this::handleCall),
        "/admin/faults", Http.safe(this::handleFaults),
        "/admin/limits", Http.safe(this::handleLimits),
        "/healthz", ex -> Http.text(ex, 200, "ok"),
        "/metrics", reg.handler(() -> {
          gInflight.set(inflight.get(), "payments");
          gPeak.set(inflightPeak.getAndSet(inflight.get()), "payments");
        }));
  }

  /** For tests. */
  public Registry registry() {
    return reg;
  }
}
