package lab.api;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lab.TestHttp;
import lab.sizing.Plan;
import org.junit.Test;

public class PaymentsApiTest {
  static String body(String order) {
    return "{\"order_id\":\"" + order + "\",\"amount_cents\":100}";
  }

  @Test
  public void idempotencyAndDuplicates() throws Exception {
    HttpServer s = TestHttp.serve(new PaymentsApi(Plan.of(1)).routes());
    String u = TestHttp.url(s);
    try {
      TestHttp.post(u + "/v1/capture", body("o1"), Map.of());
      TestHttp.post(u + "/v1/capture", body("o1"), Map.of());
      TestHttp.post(u + "/v1/capture", body("o2"), Map.of("Idempotency-Key", "o2:capture"));
      HttpResponse<String> r = TestHttp.post(u + "/v1/capture", body("o2"), Map.of("Idempotency-Key", "o2:capture"));
      assertEquals(200, r.statusCode());
      assertEquals("true", r.headers().firstValue("Idempotent-Replayed").orElse(""));
      assertEquals("1", TestHttp.metric(u, "api_duplicate_side_effects_total{step=\"capture\"}"));
      assertEquals("1", TestHttp.metric(u, "api_idempotent_replays_total{step=\"capture\"}"));
      assertEquals("2", TestHttp.metric(u, "api_reexecutions_total{step=\"capture\"}"));
    } finally {
      s.stop(0);
    }
  }

  @Test
  public void rateLimit() throws Exception {
    HttpServer s = TestHttp.serve(new PaymentsApi(Plan.of(0.05)).routes()); // 50 req/s, burst 5
    String u = TestHttp.url(s);
    try {
      Map<Integer, AtomicInteger> codes = new ConcurrentHashMap<>();
      List<Thread> ts = new ArrayList<>();
      long start = System.nanoTime();
      for (int i = 0; i < 100; i++) {
        int n = i;
        ts.add(Thread.ofVirtual().start(() -> {
          try {
            int c = TestHttp.post(u + "/v1/authorize", body("r" + n + "-" + System.nanoTime()), Map.of()).statusCode();
            codes.computeIfAbsent(c, k -> new AtomicInteger()).incrementAndGet();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      for (Thread t : ts) t.join();
      double el = (System.nanoTime() - start) / 1e9;
      int ok = codes.getOrDefault(200, new AtomicInteger()).get();
      assertTrue("ok=" + ok + " in " + el + "s", ok <= 5 + 50 * el + 1);
      assertTrue(codes.containsKey(429));
    } finally {
      s.stop(0);
    }
  }

  @Test
  public void concurrencyLimit() throws Exception {
    PaymentsApi api = new PaymentsApi(Plan.of(1));
    HttpServer s = TestHttp.serve(api.routes());
    String u = TestHttp.url(s);
    try {
      TestHttp.post(u + "/admin/limits", "{\"rate_per_sec\":0,\"concurrency\":10}", Map.of());
      TestHttp.post(u + "/admin/faults", "{\"latency_mult\":10}", Map.of());
      Map<Integer, AtomicInteger> codes = new ConcurrentHashMap<>();
      List<String> reasons = Collections.synchronizedList(new ArrayList<>());
      List<Thread> ts = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        int n = i;
        ts.add(Thread.ofVirtual().start(() -> {
          try {
            HttpResponse<String> r = TestHttp.post(u + "/v1/capture", body("c" + n), Map.of());
            codes.computeIfAbsent(r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
            r.headers().firstValue("X-Reject-Reason").ifPresent(reasons::add);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      for (Thread t : ts) t.join();
      assertTrue(codes.toString(), codes.getOrDefault(200, new AtomicInteger()).get() <= 12);
      assertTrue(codes.toString(), codes.containsKey(429));
      assertTrue(reasons.stream().allMatch("concurrency"::equals));
      assertNotNull(TestHttp.metric(u, "api_inflight_peak{service=\"payments\"}"));
    } finally {
      s.stop(0);
    }
  }

  @Test
  public void latencyProfile() {
    double[] xs = new double[20000];
    for (int i = 0; i < xs.length; i++) xs[i] = PaymentsApi.sampleLatency(PaymentsApi.PROFILES.get("authorize"), 1);
    java.util.Arrays.sort(xs);
    double p50 = xs[xs.length / 2], p99 = xs[xs.length * 99 / 100];
    assertTrue("p50=" + p50, p50 > 0.036 && p50 < 0.044);
    assertTrue("p99=" + p99, p99 > 0.13 && p99 < 0.17);
  }

  @Test
  public void hashMatchesGoVersion() {
    // FNV-1a 64 of "x" mod 1e6 / 1e6, same function as the Go lab (stable invalid-card orders).
    long h = 0xcbf29ce484222325L;
    h ^= 'x';
    h *= 0x100000001b3L;
    assertEquals(Long.remainderUnsigned(h, 1_000_000) / 1_000_000.0, PaymentsApi.hashUnit("x"), 0);
  }

  @Test
  public void faults() throws Exception {
    HttpServer s = TestHttp.serve(new PaymentsApi(Plan.of(1)).routes());
    String u = TestHttp.url(s);
    try {
      TestHttp.post(u + "/admin/faults", "{\"outage\":true}", Map.of());
      assertEquals(503, TestHttp.post(u + "/v1/authorize", body("f1"), Map.of()).statusCode());
      assertEquals(200, TestHttp.post(u + "/v1/receipt", body("f1"), Map.of()).statusCode()); // not a payments endpoint
      TestHttp.post(u + "/admin/faults", "{\"retry_after_rate\":1,\"retry_after_sec\":40,\"steps\":[\"capture\"]}", Map.of());
      HttpResponse<String> r = TestHttp.post(u + "/v1/capture", body("f2"), Map.of());
      assertEquals(429, r.statusCode());
      assertEquals("40", r.headers().firstValue("Retry-After").orElse(""));
      assertEquals(200, TestHttp.post(u + "/v1/authorize", body("f2"), Map.of()).statusCode());
    } finally {
      s.stop(0);
    }
  }
}
