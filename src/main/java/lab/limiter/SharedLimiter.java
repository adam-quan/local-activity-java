package lab.limiter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import lab.Json;
import lab.http.Http;
import lab.prom.Registry;

/**
 * Minimal fleet-wide rate limiter (GCRA with bounded waits), standing in for the Redis- or
 * gateway-based limiter the guide recommends when an API's limit is strict and global.
 */
public final class SharedLimiter {
  private double rate;
  private double burst;
  private long tatNanos = System.nanoTime(); // theoretical arrival time

  private final Registry reg = new Registry();
  private final Registry.Counter acquired = reg.counter("limiter_acquired_total", "Tokens granted.", "key");
  private final Registry.Counter rejected = reg.counter("limiter_rejected_total", "Requests that could not get a token within their max wait.", "key");
  private final Registry.Histogram wait = reg.histogram("limiter_wait_seconds", "Time callers waited for a token.", Registry.LATENCY_BUCKETS, "key");
  private final Registry.Gauge gRate = reg.gauge("limiter_rate_per_sec", "Configured fleet-wide rate.", "key");

  public SharedLimiter(double rate) {
    setRate(rate);
  }

  public synchronized void setRate(double r) {
    rate = r;
    burst = Math.max(1, r * 0.05);
    gRate.set(r, "payments");
  }

  public synchronized double rate() {
    return rate;
  }

  /** Returns the wait in nanos, or -wait-1 if the wait would exceed maxWaitNanos. */
  synchronized long reserve(long now, long maxWaitNanos) {
    if (rate <= 0) return 0;
    long interval = (long) (1e9 / rate);
    long tolerance = (long) (interval * (burst - 1));
    long tat = Math.max(tatNanos, now);
    long w = Math.max(0, tat - now - tolerance);
    if (w > maxWaitNanos) return -w - 1;
    tatNanos = tat + interval;
    return w;
  }

  private void handleAcquire(HttpExchange ex) throws IOException {
    Map<String, String> q = Http.query(ex);
    String key = q.getOrDefault("key", "payments");
    long maxWait = 500_000_000L;
    try {
      maxWait = Long.parseLong(q.getOrDefault("max_wait_ms", "500")) * 1_000_000L;
    } catch (NumberFormatException ignored) {
    }
    long w = reserve(System.nanoTime(), maxWait);
    if (w < 0) {
      rejected.inc(key);
      ex.getResponseHeaders().set("X-Retry-After-Ms", Long.toString((-w - 1) / 1_000_000));
      Http.send(ex, 429, "text/plain", new byte[0]);
      return;
    }
    if (w > 0) {
      try {
        Thread.sleep(w / 1_000_000, (int) (w % 1_000_000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    acquired.inc(key);
    wait.observe(w / 1e9, key);
    Http.send(ex, 200, "text/plain", new byte[0]);
  }

  /** Config body. */
  public static final class Config {
    public double ratePerSec;

    public Config() {}
  }

  private void handleConfig(HttpExchange ex) throws IOException {
    if ("POST".equals(ex.getRequestMethod())) {
      setRate(Json.read(ex.getRequestBody(), Config.class).ratePerSec);
    }
    Config c = new Config();
    c.ratePerSec = rate();
    Http.json(ex, 200, c);
  }

  public Map<String, HttpHandler> routes() {
    return Map.of(
        "/acquire", Http.safe(this::handleAcquire),
        "/admin/config", Http.safe(this::handleConfig),
        "/healthz", ex -> Http.text(ex, 200, "ok"),
        "/metrics", reg.handler(null));
  }
}
