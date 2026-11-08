package lab.app;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lab.Json;
import lab.app.Model.CallRequest;
import lab.app.Model.CallResult;
import lab.prom.Registry;

/**
 * Calls the mock API and maps responses to Temporal failures. One instance per worker, so each
 * worker has its own connection pool (sized to its local activity slots) and its own local
 * activity rate limiter.
 */
public class PaymentsActivitiesImpl implements PaymentsActivities {
  public static final Set<String> PAYMENTS_STEPS = Set.of("authorize", "capture", "fraud", "tax", "ledger");

  private final String apiUrl;
  private final String limiterUrl;
  private final HttpClient http;
  private final Semaphore pool; // models a connection pool of N connections
  private final TokenBucket laRate; // per-worker local activity rate limit (Java has no SDK option)
  private final Breakers breakers;
  private final Registry reg;
  private final Map<String, String> tags; // process + worker_id

  public PaymentsActivitiesImpl(
      String apiUrl, String limiterUrl, int poolSize, double laRatePerSec, Breakers breakers, Registry reg, Map<String, String> tags) {
    this.apiUrl = apiUrl;
    this.limiterUrl = limiterUrl;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    this.pool = new Semaphore(Math.max(1, poolSize), true);
    this.laRate = new TokenBucket(laRatePerSec);
    this.breakers = breakers;
    this.reg = reg;
    this.tags = tags;
  }

  private void count(CallRequest req, String outcome) {
    if (reg == null) return;
    reg.dynamicCounter("demo_api_calls_total",
        with(Map.of("step", req.step, "outcome", outcome, "policy", req.policy == null ? "" : req.policy)), 1);
  }

  private Map<String, String> with(Map<String, String> extra) {
    Map<String, String> m = new java.util.HashMap<>(tags);
    m.putAll(extra);
    return m;
  }

  /** Retry-After from X-Retry-After-Ms (preferred) or Retry-After seconds. */
  static Duration retryAfter(HttpResponse<?> resp) {
    var ms = resp.headers().firstValue("X-Retry-After-Ms");
    if (ms.isPresent()) {
      try {
        return Duration.ofMillis(Long.parseLong(ms.get()));
      } catch (NumberFormatException ignored) {
      }
    }
    var s = resp.headers().firstValue("Retry-After");
    if (s.isPresent()) {
      try {
        return Duration.ofSeconds(Long.parseLong(s.get()));
      } catch (NumberFormatException ignored) {
      }
    }
    return Duration.ZERO;
  }

  /** Time left in this attempt, from StartToClose (approximate: measured from activity start). */
  private static long attemptBudgetNanos() {
    try {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      Duration stc = info.getStartToCloseTimeout();
      if (stc != null && !stc.isZero()) return stc.toNanos();
    } catch (RuntimeException ignored) {
      // not running inside an activity (unit tests)
    }
    return Duration.ofSeconds(30).toNanos();
  }

  private static boolean isLocal() {
    try {
      return Activity.getExecutionContext().getInfo().isLocal();
    } catch (RuntimeException e) {
      return true;
    }
  }

  @Override
  public CallResult callApi(CallRequest req) {
    long start = System.nanoTime();
    long deadline = start + attemptBudgetNanos();
    Breakers.Breaker br = null;
    if (req.circuitBreaker && breakers != null) {
      br = breakers.get(req.step);
      if (!br.allow()) {
        count(req, "circuit_open");
        throw Failures.fail("circuit breaker open for " + req.step, Names.ERR_CIRCUIT_OPEN, true, null);
      }
    }
    boolean payments = PAYMENTS_STEPS.contains(req.step);
    try {
      // Per-worker local activity rate limit (in code: holds the slot while waiting).
      if (payments && isLocal() && laRate.rate() > 0) {
        long maxWait = Math.max(0, (deadline - System.nanoTime()) / 2);
        long w0 = System.nanoTime();
        boolean got = laRate.acquire(maxWait);
        if (reg != null) reg.dynamicObserve("demo_la_rate_wait_seconds", tags, (System.nanoTime() - w0) / 1e9);
        if (!got) {
          count(req, "worker_rate_wait_exceeded");
          throw Failures.fail("no per-worker rate token in time", Names.ERR_LIMITER_TIMEOUT, false, Duration.ofMillis(50));
        }
      }
      if (req.sharedLimiter && payments && limiterUrl != null && !limiterUrl.isEmpty()) {
        acquireShared(req, deadline);
      }
      return call(req, br, deadline);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Failures.fail("interrupted", Names.ERR_UNAVAILABLE, false, null);
    }
  }

  private void acquireShared(CallRequest req, long deadline) throws InterruptedException {
    long maxWaitMs = Math.max(0, Math.min(500, (deadline - System.nanoTime()) / 1_000_000 - 100));
    HttpRequest r = HttpRequest.newBuilder(URI.create(limiterUrl + "/acquire?key=payments&max_wait_ms=" + maxWaitMs))
        .timeout(Duration.ofMillis(maxWaitMs + 1000)).GET().build();
    HttpResponse<Void> resp;
    try {
      resp = http.send(r, HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      throw Failures.fail("shared limiter unreachable: " + e.getMessage(), Names.ERR_UNAVAILABLE, false, null);
    }
    if (resp.statusCode() != 200) {
      count(req, "limiter_wait_exceeded");
      throw Failures.fail("no token from the shared limiter in time", Names.ERR_LIMITER_TIMEOUT, false, retryAfter(resp));
    }
  }

  private CallResult call(CallRequest req, Breakers.Breaker br, long deadline) throws InterruptedException {
    String body = Json.string(Map.of("order_id", req.orderId, "amount_cents", req.amountCents));
    long start = System.nanoTime();
    long left = deadline - start;
    // Pool wait counts against StartToClose but shows in no Temporal metric.
    if (left <= 0 || !pool.tryAcquire(left, TimeUnit.NANOSECONDS)) {
      if (br != null) br.record(false);
      count(req, "pool_wait_timeout");
      throw Failures.fail("no pooled connection before the attempt deadline", Names.ERR_UNAVAILABLE, false, null);
    }
    HttpResponse<byte[]> resp;
    try {
      long remaining = Math.max(1_000_000, deadline - System.nanoTime());
      HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(apiUrl + "/v1/" + req.step))
          .timeout(Duration.ofNanos(remaining))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
      if (req.idempotencyKey != null && !req.idempotencyKey.isEmpty()) rb.header("Idempotency-Key", req.idempotencyKey);
      resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      if (br != null) br.record(false);
      String outcome = e instanceof HttpTimeoutException ? "client_timeout" : "network_error";
      count(req, outcome);
      throw Failures.fail(outcome + ": " + e.getMessage(), Names.ERR_UNAVAILABLE, false, null);
    } finally {
      pool.release();
      if (reg != null) {
        reg.dynamicObserve("demo_api_call_latency_seconds", with(Map.of("step", req.step)), (System.nanoTime() - start) / 1e9);
      }
    }

    int code = resp.statusCode();
    if (code >= 200 && code < 300) {
      if (br != null) br.record(true);
      CallResult out = new CallResult();
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = Json.MAPPER.readValue(resp.body(), Map.class);
        out.id = String.valueOf(m.get("id"));
      } catch (IOException ignored) {
      }
      out.replayed = "true".equals(resp.headers().firstValue("Idempotent-Replayed").orElse(""));
      count(req, out.replayed ? "ok_replayed" : "ok");
      return out;
    }
    if (code == 400 || code == 401 || code == 403 || code == 404 || code == 409 || code == 422) {
      if (br != null) br.record(true); // the API is healthy; the request is bad
      count(req, "invalid_request");
      // Retryable by default: only the policy's DoNotRetry list stops it.
      throw Failures.fail("HTTP " + code + " from " + req.step, Names.ERR_INVALID_REQUEST, false, null);
    }
    if (code == 429) {
      Duration wait = retryAfter(resp);
      String reason = resp.headers().firstValue("X-Reject-Reason").orElse("");
      if (req.maxRetryAfterMs > 0 && wait.toMillis() > req.maxRetryAfterMs) {
        count(req, "retry_after_too_long");
        throw Failures.fail("429 (" + reason + "), Retry-After " + wait + " exceeds budget", Names.ERR_RETRY_AFTER_TOO_LONG, true, null);
      }
      count(req, "rate_limited_" + reason);
      throw Failures.fail("429 (" + reason + "), Retry-After " + wait, Names.ERR_RATE_LIMITED, false, wait);
    }
    if (br != null) br.record(false);
    count(req, "server_error");
    throw Failures.fail("HTTP " + code + " from " + req.step, Names.ERR_UNAVAILABLE, false, null);
  }
}
