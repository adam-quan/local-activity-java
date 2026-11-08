package lab.runner;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lab.Json;
import lab.TestHttp;
import lab.api.PaymentsApi;
import lab.fleet.Fleet;
import lab.fleet.FleetConfig;
import lab.http.Http;
import lab.limiter.SharedLimiter;
import lab.sizing.Plan;
import org.junit.Test;

/** The runner's wiring against the real API and limiter, fake worker processes and a fake Grafana. */
public class RunnerWiringTest {
  /** Records configs pushed to it; /crash simulates a restart with no workers. */
  static final class FakeWorker {
    final List<FleetConfig> configs = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong boot = new AtomicLong(1);
    final AtomicInteger crashes = new AtomicInteger(), shutdowns = new AtomicInteger();
    final HttpServer srv;

    FakeWorker() throws Exception {
      srv = TestHttp.serve(Map.of(
          "/config", Http.safe(ex -> {
            if ("POST".equals(ex.getRequestMethod())) configs.add(Json.read(ex.getRequestBody(), FleetConfig.class));
            Fleet.Status st = new Fleet.Status();
            st.boot = boot.get();
            FleetConfig last = last();
            if (last != null) {
              st.config = last;
              for (int i = 0; i < last.workers; i++) st.workers.add("w" + i);
            }
            Http.json(ex, 200, st);
          }),
          "/crash", ex -> {
            crashes.incrementAndGet();
            boot.incrementAndGet();
            configs.clear();
            Http.send(ex, 202, "text/plain", new byte[0]);
          },
          "/shutdown", ex -> {
            shutdowns.incrementAndGet();
            Http.send(ex, 202, "text/plain", new byte[0]);
          }));
    }

    FleetConfig last() {
      synchronized (configs) {
        return configs.isEmpty() ? null : configs.get(configs.size() - 1);
      }
    }
  }

  @Test
  public void applyActionsAnnotationsAndInfo() throws Exception {
    Plan plan = Plan.of(0.2);
    HttpServer api = TestHttp.serve(new PaymentsApi(plan).routes());
    HttpServer lim = TestHttp.serve(new SharedLimiter(plan.apiRateLimit).routes());
    FakeWorker wa = new FakeWorker(), wb = new FakeWorker();
    List<String> grafanaCalls = Collections.synchronizedList(new ArrayList<>());
    HttpServer graf = TestHttp.serve(Map.of("/api/annotations", ex -> {
      grafanaCalls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("Authorization"));
      Http.send(ex, 200, "application/json", "{\"id\":42}".getBytes());
    }));
    try {
      List<WorkerProc> procs = List.of(new WorkerProc("worker-a", TestHttp.url(wa.srv)), new WorkerProc("worker-b", TestHttp.url(wb.srv)));
      Runner r = new Runner(new Runner.Options(plan, "", "default", TestHttp.url(api), TestHttp.url(lim), procs,
          new Grafana(TestHttp.url(graf), "admin", "admin"), "http://localhost:3000", "http://localhost:8233"), List.of());
      Scenario scaleOut = r.scenarios().stream().filter(s -> s.id.equals("fleet-scale-out")).findFirst().orElseThrow();

      r.apply(scaleOut.phases.get(1)); // 10 workers, caps sized for 6
      assertEquals(5, wa.last().workers);
      assertEquals(5, wb.last().workers);
      assertTrue(wa.last().laRate > 33);
      assertEquals(plan.laSlotsPerWorker, wa.last().laSlots);

      r.apply(scaleOut.phases.get(3)); // shared limiter, no per-worker cap
      assertEquals(0, wa.last().laRate, 0);
      assertEquals(plan.apiRateLimit, Json.read(TestHttp.get(TestHttp.url(lim) + "/admin/config").body().getBytes(), SharedLimiter.Config.class).ratePerSec, 0);
      assertEquals("scaleout", r.load().streams().get(0).policy.name);
      assertTrue(r.load().streams().get(0).policy.sharedLimiter);

      Scenario slow = r.scenarios().stream().filter(s -> s.id.equals("api-slowdown")).findFirst().orElseThrow();
      r.apply(slow.phases.get(1));
      PaymentsApi.Faults f = Json.read(TestHttp.get(TestHttp.url(api) + "/admin/faults").body().getBytes(), PaymentsApi.Faults.class);
      assertEquals(15, f.latencyMult, 0);

      // Crash: the watcher notices the new boot id and re-applies the config.
      Thread watcher = Thread.ofVirtual().start(procs.get(0)::watch);
      r.act(slow, new Action(0, "crash", "worker-a"));
      long deadline = System.currentTimeMillis() + 5000;
      while ((wa.last() == null || wa.last().workers != 4) && System.currentTimeMillis() < deadline) Thread.sleep(100);
      watcher.interrupt();
      assertEquals(1, wa.crashes.get());
      assertEquals(4, wa.last().workers);
      r.act(slow, new Action(0, "graceful", "worker-a"));
      assertEquals(1, wa.shutdowns.get());

      long id = new Grafana(TestHttp.url(graf), "admin", "admin").start("x", List.of("demo", "phase"));
      assertEquals(42, id);
      new Grafana(TestHttp.url(graf), "admin", "admin").end(id);
      String calls = String.join("\n", grafanaCalls);
      assertTrue(calls, calls.contains("POST /api/annotations Basic YWRtaW46YWRtaW4="));
      assertTrue(calls, calls.contains("PATCH /api/annotations/42"));

      // /api/info has the shape the (shared) control page expects.
      HttpServer ui = TestHttp.serve(r.routes());
      try {
        JsonNode info = Json.MAPPER.readTree(TestHttp.get(TestHttp.url(ui) + "/api/info").body());
        assertEquals(60.0, info.at("/plan/workflows_per_sec").asDouble(), 1e-9);
        assertEquals(500_000_000L, info.at("/plan/start_to_close").asLong());
        assertEquals(4, info.at("/plan/la_slots_per_worker").asInt());
        assertEquals("sizing", info.at("/scenarios/0/id").asText());
        assertEquals("la-fm-api-slowdown", info.at("/scenarios/1/dashboard").asText());
        assertTrue(info.at("/scenarios/0/total_seconds").asDouble() > 0);
        assertTrue(info.at("/scenarios/0/steps/0/load_wfps").asDouble() > 0);
        JsonNode st = Json.MAPPER.readTree(TestHttp.get(TestHttp.url(ui) + "/api/state").body());
        assertTrue(st.has("phase_index") && st.has("phase_ends") && st.has("paused"));
        assertTrue(TestHttp.get(TestHttp.url(ui) + "/").body().contains("Local Activity Lab"));
        assertTrue(TestHttp.get(TestHttp.url(ui) + "/metrics").body().contains("demo_sizing_planned{quantity=\"min_workers\"} 8"));
      } finally {
        ui.stop(0);
      }
    } finally {
      api.stop(0);
      lim.stop(0);
      wa.srv.stop(0);
      wb.srv.stop(0);
      graf.stop(0);
    }
  }
}
