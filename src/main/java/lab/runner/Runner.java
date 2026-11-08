package lab.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lab.api.PaymentsApi.Faults;
import lab.fleet.FleetConfig;
import lab.http.Http;
import lab.limiter.SharedLimiter;
import lab.prom.Registry;
import lab.sizing.Plan;

/**
 * The scenario engine: generates checkout load, applies each phase (fleet size and worker
 * settings, API faults, workflow policy), crashes or gracefully restarts worker processes, and
 * marks every phase on the Grafana dashboards with region annotations.
 */
public final class Runner {
  /** Runner settings. */
  public record Options(
      Plan plan, String temporalAddress, String namespace, String apiUrl, String limiterUrl, List<WorkerProc> workers,
      Grafana grafana, String grafanaPublic, String temporalUi) {}

  private final Options opts;
  private final Registry reg = new Registry();
  private final Load load;
  private final List<Scenario> catalog;
  private final Phase idle;

  private State state = new State();
  private List<Stream> current = List.of();
  private AtomicBoolean cancel;
  private CountDownLatch done;

  private final Registry.Gauge gVariant = reg.gauge("demo_scenario_variant", "Active scenario: 1 baseline, 2 broken, 3 fixed.", "scenario");
  private final Registry.Gauge gPhase = reg.gauge("demo_phase_info", "1 for the active phase.", "scenario", "phase", "variant");
  private final Registry.Gauge gPlan = reg.gauge("demo_sizing_planned", "The guide's worked sizing example, derived at this SCALE.", "quantity");
  private final Registry.Gauge gFleet = reg.gauge("demo_fleet_target_workers", "Virtual workers the runner asked for (all processes).");

  private static final Map<String, Double> VARIANT = Map.of(Scenarios.BASELINE, 1.0, Scenarios.BROKEN, 2.0, Scenarios.FIXED, 3.0);

  /** Connects to Temporal (with retries) and builds the runner. */
  public static Runner connect(Options opts) throws InterruptedException {
    List<WorkflowClient> clients = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      WorkflowServiceStubs stubs = null;
      for (int attempt = 0; attempt < 90 && stubs == null; attempt++) {
        try {
          stubs = WorkflowServiceStubs.newConnectedServiceStubs(
              WorkflowServiceStubsOptions.newBuilder().setTarget(opts.temporalAddress()).build(), Duration.ofSeconds(5));
        } catch (RuntimeException e) {
          Thread.sleep(1000);
        }
      }
      if (stubs == null) throw new IllegalStateException("cannot reach Temporal at " + opts.temporalAddress());
      clients.add(WorkflowClient.newInstance(stubs,
          WorkflowClientOptions.newBuilder().setNamespace(opts.namespace()).setIdentity("runner-" + i).build()));
    }
    return new Runner(opts, clients);
  }

  Runner(Options opts, List<WorkflowClient> clients) {
    this.opts = opts;
    this.load = new Load(clients, reg);
    this.catalog = Scenarios.catalog(opts.plan());
    this.idle = Scenarios.phase("steady state (sized)", Scenarios.BASELINE, 0, opts.plan().minWorkers,
        Scenarios.sizedWorker(opts.plan()), Scenarios.normal(), null,
        Scenarios.stream(opts.plan().workflowsPerSec, Scenarios.sizedPolicy("steady")));
    publishPlan();
  }

  private void publishPlan() {
    Plan p = opts.plan();
    Map<String, Double> q = new LinkedHashMap<>();
    q.put("scale", p.scale);
    q.put("workflows_per_sec", p.workflowsPerSec);
    q.put("api_calls_per_sec", p.apiCallsPerSec);
    q.put("api_rate_limit", p.apiRateLimit);
    q.put("api_concurrency", (double) p.apiConcurrency);
    q.put("max_workers", (double) p.maxWorkers);
    q.put("min_workers", (double) p.minWorkers);
    q.put("la_slots_per_worker", (double) p.laSlotsPerWorker);
    q.put("la_rate_per_worker", p.laRatePerWorker);
    q.put("calls_per_worker_at_min", p.callsPerWorkerAtMin);
    q.put("peak_concurrent_per_worker", p.peakConcurrentPerWorker);
    q.put("wft_per_sec_per_worker", p.wftPerSecPerWorker);
    q.put("concurrent_wft_per_worker", p.concurrentWftPerWorker);
    q.put("wft_slots_per_worker", (double) p.wftSlotsPerWorker);
    q.put("start_to_close_seconds", p.startToClose / 1e9);
    q.put("schedule_to_close_seconds", p.scheduleToClose / 1e9);
    q.put("worst_case_wft_seconds", p.worstCaseWft / 1e9);
    q.put("heartbeat_point_seconds", p.heartbeatPoint / 1e9);
    q.put("http_pool_per_worker", (double) p.httpPoolPerWorker);
    q.put("fleet_rate_cap_at_min", p.fleetRateCapAtMin);
    q.put("fleet_rate_cap_below_min", p.fleetRateCapBelowMin);
    q.put("api_p50_seconds", p.apiP50 / 1e9);
    q.put("api_p99_seconds", p.apiP99 / 1e9);
    q.forEach((k, v) -> gPlan.set(v, k));
  }

  /** Starts the watchers, the load loop and the idle phase. */
  public void start() {
    for (WorkerProc w : opts.workers()) Thread.ofVirtual().start(w::watch);
    load.start();
    try {
      apply(idle);
    } catch (Exception e) {
      setErr(e);
    }
    synchronized (this) {
      state = idleState(false);
    }
    markIdle();
  }

  private State idleState(boolean paused) {
    State s = new State();
    s.scenario = "idle";
    s.phase = idle.name;
    s.variant = Scenarios.BASELINE;
    s.started = Instant.now().toString();
    s.paused = paused;
    return s;
  }

  private synchronized void setErr(Exception e) {
    System.err.println("runner error: " + e.getMessage());
    state.lastError = String.valueOf(e.getMessage());
  }

  private void markIdle() {
    gVariant.reset();
    gPhase.reset();
    gVariant.set(0, "idle");
  }

  /** Divides the fleet across worker processes (worker-a gets the extra one). */
  static int[] split(int total, int procs) {
    int[] out = new int[procs];
    for (int i = 0; i < procs; i++) out[i] = total / procs + (i < total % procs ? 1 : 0);
    return out;
  }

  /** Pushes a phase's configuration everywhere. */
  void apply(Phase p) throws Exception {
    List<String> errs = new ArrayList<>();
    Faults f = p.faults == null ? Scenarios.normal() : p.faults;
    try {
      Http.postJson(opts.apiUrl() + "/admin/faults", f, null, Duration.ofSeconds(10));
    } catch (Exception e) {
      errs.add(e.getMessage());
    }
    SharedLimiter.Config lc = new SharedLimiter.Config();
    lc.ratePerSec = p.limiter > 0 ? p.limiter : opts.plan().apiRateLimit;
    try {
      Http.postJson(opts.limiterUrl() + "/admin/config", lc, null, Duration.ofSeconds(10));
    } catch (Exception e) {
      errs.add(e.getMessage());
    }
    int[] shares = split(p.workers, opts.workers().size());
    List<Thread> ts = new ArrayList<>();
    for (int i = 0; i < opts.workers().size(); i++) {
      WorkerProc w = opts.workers().get(i);
      FleetConfig cfg = p.worker.copy();
      cfg.workers = shares[i];
      w.setDesired(cfg);
      ts.add(Thread.ofVirtual().start(() -> {
        try {
          w.push();
        } catch (Exception e) {
          synchronized (errs) {
            errs.add(w.name + ": " + e.getMessage());
          }
        }
      }));
    }
    for (Thread t : ts) t.join();
    gFleet.set(p.workers);
    boolean paused;
    synchronized (this) {
      current = p.streams;
      paused = state.paused;
    }
    load.setStreams(paused ? List.of() : p.streams);
    if (!errs.isEmpty()) throw new IllegalStateException("apply \"" + p.name + "\": " + errs);
  }

  public List<Scenario> scenarios() {
    return catalog;
  }

  public synchronized State state() {
    return state.copy();
  }

  /** Starts a scenario (stopping any running one first). */
  public void run(String id) {
    Scenario sc = catalog.stream().filter(s -> s.id.equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + id));
    stop();
    AtomicBoolean c = new AtomicBoolean();
    CountDownLatch d = new CountDownLatch(1);
    synchronized (this) {
      cancel = c;
      done = d;
      boolean paused = state.paused;
      state = new State();
      state.scenario = sc.id;
      state.started = Instant.now().toString();
      state.phases = sc.phases.size();
      state.paused = paused;
    }
    Thread.ofVirtual().start(() -> {
      try {
        runScenario(sc, c);
      } finally {
        d.countDown();
      }
    });
  }

  /** Cancels the running scenario and waits until it has restored steady state. */
  public void stop() {
    AtomicBoolean c;
    CountDownLatch d;
    synchronized (this) {
      c = cancel;
      d = done;
      cancel = null;
      done = null;
    }
    if (c != null) {
      c.set(true);
      try {
        d.await(2, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Pauses or resumes all load. */
  public void setPaused(boolean p) {
    List<Stream> cur;
    synchronized (this) {
      state.paused = p;
      cur = current;
    }
    load.setStreams(p ? List.of() : cur);
  }

  /** Sleeps in small steps so cancellation is prompt. Returns false if cancelled. */
  private static boolean sleep(AtomicBoolean cancel, long millis) {
    long end = System.currentTimeMillis() + millis;
    while (!cancel.get()) {
      long left = end - System.currentTimeMillis();
      if (left <= 0) return true;
      try {
        Thread.sleep(Math.min(left, 200));
      } catch (InterruptedException e) {
        return false;
      }
    }
    return false;
  }

  private void runScenario(Scenario sc, AtomicBoolean cancel) {
    long scenarioAnn = opts.grafana().start("<b>" + sc.title + "</b> started", List.of("demo", "scenario", sc.id));
    try {
      for (int i = 0; i < sc.phases.size() && !cancel.get(); i++) {
        Phase p = sc.phases.get(i);
        System.err.println("scenario " + sc.id + ": phase " + p.name + " (" + p.variant + ")");
        try {
          apply(p);
        } catch (Exception e) {
          setErr(e);
        }
        synchronized (this) {
          state.phase = p.name;
          state.variant = p.variant;
          state.note = p.note;
          state.phaseIndex = i + 1;
          state.phaseEnds = Instant.now().plusSeconds(p.durationSec).toString();
        }
        gVariant.reset();
        gVariant.set(VARIANT.get(p.variant), sc.id);
        gPhase.reset();
        gPhase.set(1, sc.id, p.name, p.variant);

        String text = "<b>" + sc.title + "</b> · " + p.variant + " · " + p.name + (p.note.isEmpty() ? "" : "<br/>" + p.note);
        long ann = opts.grafana().start(text, List.of("demo", "phase", sc.id, p.variant));
        long phaseStart = System.currentTimeMillis();
        List<Action> actions = new ArrayList<>(p.actions);
        actions.sort(Comparator.comparingInt(a -> a.atSec));
        boolean ok = true;
        for (Action a : actions) {
          ok = sleep(cancel, phaseStart + a.atSec * 1000L - System.currentTimeMillis());
          if (!ok) break;
          act(sc, a);
        }
        if (ok) ok = sleep(cancel, phaseStart + p.durationSec * 1000L - System.currentTimeMillis());
        opts.grafana().end(ann);
        if (!ok) break;
      }
    } finally {
      opts.grafana().end(scenarioAnn);
      try {
        apply(idle);
      } catch (Exception e) {
        setErr(e);
      }
      synchronized (this) {
        state = idleState(state.paused);
      }
      markIdle();
      System.err.println("scenario " + sc.id + " finished");
    }
  }

  void act(Scenario sc, Action a) {
    for (WorkerProc w : opts.workers()) {
      if (!w.name.equals(a.target)) continue;
      try {
        if ("crash".equals(a.kind)) {
          opts.grafana().point("<b>" + w.name + " killed</b> (in-flight workflow tasks abandoned)", List.of("demo", "action", sc.id));
          w.crash();
        } else if ("graceful".equals(a.kind)) {
          opts.grafana().point("<b>" + w.name + " graceful restart</b> (shutdown() + awaitTermination drains in-flight tasks)", List.of("demo", "action", sc.id));
          w.graceful();
        }
      } catch (Exception e) {
        System.err.println("action " + a.kind + " on " + a.target + ": " + e.getMessage());
      }
    }
  }

  /** Phase summary for the control page. */
  public static final class PhaseSummary {
    public String name, variant, note;
    public double seconds, loadWfps;
    public int workers;

    public PhaseSummary() {}
  }

  /** Scenario as shown on the control page (no phases, plus totals). */
  public static final class ScenarioView {
    public String id, title, guide, symptom, cause, fix, watch, dashboard;
    public double totalSeconds;
    public List<PhaseSummary> steps = new ArrayList<>();

    public ScenarioView() {}
  }

  static ScenarioView view(Scenario s) {
    ScenarioView v = new ScenarioView();
    v.id = s.id;
    v.title = s.title;
    v.guide = s.guide;
    v.symptom = s.symptom;
    v.cause = s.cause;
    v.fix = s.fix;
    v.watch = s.watch;
    v.dashboard = s.dashboard;
    v.totalSeconds = s.totalSec();
    for (Phase p : s.phases) {
      PhaseSummary ps = new PhaseSummary();
      ps.name = p.name;
      ps.variant = p.variant;
      ps.note = p.note;
      ps.seconds = p.durationSec;
      ps.workers = p.workers;
      ps.loadWfps = Math.round(p.streams.stream().mapToDouble(st -> st.rate).sum() * 10) / 10.0;
      v.steps.add(ps);
    }
    return v;
  }

  private static byte[] uiHtml() throws IOException {
    try (InputStream in = Runner.class.getResourceAsStream("/lab/ui.html")) {
      if (in == null) throw new IOException("ui.html missing from the classpath");
      return in.readAllBytes();
    }
  }

  private void post(HttpExchange ex, Runnable r) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) {
      Http.text(ex, 405, "POST only");
      return;
    }
    r.run();
    Http.json(ex, 200, state());
  }

  /** Control page, JSON API and /metrics. */
  public Map<String, HttpHandler> routes() {
    Map<String, HttpHandler> m = new LinkedHashMap<>();
    m.put("/", Http.safe(ex -> {
      if (!ex.getRequestURI().getPath().equals("/")) {
        Http.text(ex, 404, "not found");
        return;
      }
      Http.send(ex, 200, "text/html; charset=utf-8", uiHtml());
    }));
    m.put("/api/info", Http.safe(ex -> {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("plan", opts.plan());
      info.put("scenarios", catalog.stream().map(Runner::view).toList());
      info.put("grafana", opts.grafanaPublic());
      info.put("temporal_ui", opts.temporalUi());
      Http.json(ex, 200, info);
    }));
    m.put("/api/state", Http.safe(ex -> Http.json(ex, 200, state())));
    m.put("/api/run", Http.safe(ex -> {
      if (!"POST".equals(ex.getRequestMethod())) {
        Http.text(ex, 405, "POST only");
        return;
      }
      try {
        run(Http.query(ex).getOrDefault("scenario", ""));
      } catch (IllegalArgumentException e) {
        Http.text(ex, 400, e.getMessage());
        return;
      }
      Http.json(ex, 200, state());
    }));
    m.put("/api/stop", Http.safe(ex -> post(ex, this::stop)));
    m.put("/api/pause", Http.safe(ex -> post(ex, () -> setPaused(!"false".equals(Http.query(ex).get("on"))))));
    m.put("/metrics", reg.handler(null));
    m.put("/healthz", ex -> Http.text(ex, 200, "ok"));
    return m;
  }

  /** For tests. */
  Load load() {
    return load;
  }
}
