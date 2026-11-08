package lab.fleet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.CompositeTuner;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.JVMSystemResourceInfo;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.worker.tuning.ResourceBasedController;
import io.temporal.worker.tuning.ResourceBasedControllerOptions;
import io.temporal.worker.tuning.ResourceBasedSlotOptions;
import io.temporal.worker.tuning.ResourceBasedSlotSupplier;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lab.Json;
import lab.app.Breakers;
import lab.app.CheckoutWorkflowImpl;
import lab.app.Names;
import lab.app.PaymentsActivitiesImpl;
import lab.http.Http;
import lab.prom.Registry;

/**
 * Runs a set of "virtual" Temporal workers inside one JVM. Each virtual worker has its own
 * service stubs (gRPC channel), client identity, WorkerFactory, local activity slots, rate limiter
 * and HTTP pool, exactly like a separate pod would, and reports SDK metrics tagged with its
 * worker_id. The runner resizes and reconfigures the fleet through a small control API and can
 * crash the whole JVM (pod loss) or shut it down gracefully.
 */
public final class Fleet {
  /** Workflow task pollers per virtual worker (the SDK default count, but fixed). */
  static final int WFT_POLLERS = 5;

  /** Process-level settings. */
  public record Options(String process, String temporalAddress, String namespace, String apiUrl, String limiterUrl) {}

  private record VWorker(String id, WorkflowServiceStubs stubs, WorkerFactory factory, int stopTimeoutMs) {}

  private final Options opts;
  private final Registry reg;
  private final Scope root;
  private final Breakers breakers;
  private final long boot = System.currentTimeMillis();
  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private final List<VWorker> workers = new ArrayList<>();
  private VWorker activityWorker;
  private FleetConfig cfg = new FleetConfig();

  public Fleet(Options opts, Registry reg) {
    this.opts = opts;
    this.reg = reg;
    this.root =
        new RootScopeBuilder()
            .reporter(new PromStatsReporter(reg))
            .tags(Map.of("process", opts.process()))
            .reportEvery(com.uber.m3.util.Duration.ofSeconds(1));
    this.breakers = new Breakers((step, v) -> reg.dynamicGauge("demo_circuit_breaker_state", Map.of("process", opts.process(), "step", step), v));
    Thread.ofVirtual().start(this::sysinfoLoop);
  }

  /** CPU and memory as the resource-based tuner sees them (JVM process CPU and heap). */
  private void sysinfoLoop() {
    JVMSystemResourceInfo info = new JVMSystemResourceInfo();
    while (true) {
      try {
        reg.dynamicGauge("demo_sysinfo_usage", Map.of("process", opts.process(), "resource", "cpu"), info.getCPUUsagePercent());
        reg.dynamicGauge("demo_sysinfo_usage", Map.of("process", opts.process(), "resource", "memory"), info.getMemoryUsagePercent());
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException ignored) {
        // keep going
      }
    }
  }

  private WorkflowServiceStubs connect(String id) {
    WorkflowServiceStubsOptions o =
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget(opts.temporalAddress())
            .setMetricsScope(root.tagged(Map.of("worker_id", id)))
            .build();
    RuntimeException last = null;
    for (int i = 0; i < 60; i++) {
      try {
        return WorkflowServiceStubs.newConnectedServiceStubs(o, Duration.ofSeconds(5));
      } catch (RuntimeException e) {
        last = e;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  private WorkerOptions workerOptions(String id, FleetConfig c) {
    WorkerOptions.Builder b =
        WorkerOptions.newBuilder()
            .setIdentity(id)
            .setLocalActivityWorkerOnly(true) // regular activities run on the activity-only worker
            .setUsingVirtualThreads(true)
            // Fixed, equal pollers per worker. Matching hands tasks to whichever poller is waiting,
            // so a worker's share of the load tracks its poller count. With autoscaling, a busy
            // worker scales its pollers up, wins even more tasks and saturates its local activity
            // slots and rate cap while others idle. The sizing plan assumes an even split.
            .setWorkflowTaskPollersBehavior(new PollerBehaviorSimpleMaximum(WFT_POLLERS));
    if ("resource".equals(c.tuner)) {
      // The anti-pattern from the guide: resource-based slots for I/O-bound local activities. JVM
      // CPU and heap barely move while calls wait on the network, so slots keep being issued.
      ResourceBasedController ctrl =
          ResourceBasedController.newSystemInfoController(ResourceBasedControllerOptions.newBuilder(0.9, 0.9).build());
      b.setWorkerTuner(
          new CompositeTuner(
              new FixedSizeSlotSupplier<>(c.wftSlots),
              new FixedSizeSlotSupplier<>(1),
              ResourceBasedSlotSupplier.createForLocalActivity(
                  ctrl,
                  ResourceBasedSlotOptions.newBuilder()
                      .setMinimumSlots(1)
                      .setMaximumSlots(10_000)
                      .setRampThrottle(Duration.ofMillis(50))
                      .build()),
              new FixedSizeSlotSupplier<>(1)));
    } else {
      b.setMaxConcurrentLocalActivityExecutionSize(c.laSlots).setMaxConcurrentWorkflowTaskExecutionSize(c.wftSlots);
    }
    return b.build();
  }

  private Map<String, String> tags(String id) {
    return Map.of("process", opts.process(), "worker_id", id);
  }

  private VWorker startWorker(String id, FleetConfig c) {
    WorkflowServiceStubs stubs = connect(id);
    WorkflowClient client =
        WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(opts.namespace()).setIdentity(id).build());
    WorkerFactory factory =
        WorkerFactory.newInstance(
            client,
            WorkerFactoryOptions.newBuilder()
                .setWorkflowCacheSize(600)
                .setMaxWorkflowThreadCount(600) // >= 2 x workflow task slots (guide)
                .setUsingVirtualWorkflowThreads(true)
                .build());
    Worker w = factory.newWorker(Names.TASK_QUEUE, workerOptions(id, c));
    int pool = "resource".equals(c.tuner) ? 512 : c.laSlots; // HTTP pool = local activity slots
    w.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
    w.registerActivitiesImplementations(
        new PaymentsActivitiesImpl(opts.apiUrl(), opts.limiterUrl(), pool, c.laRate, breakers, reg, tags(id)));
    factory.start();
    return new VWorker(id, stubs, factory, c.stopTimeoutMs);
  }

  private void startActivityWorker() {
    String id = opts.process() + "_activities";
    WorkflowServiceStubs stubs = connect(id);
    WorkflowClient client =
        WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(opts.namespace()).setIdentity(id).build());
    WorkerFactory factory = WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder().build());
    Worker w =
        factory.newWorker(
            Names.ACTIVITY_TASK_QUEUE,
            WorkerOptions.newBuilder().setIdentity(id).setMaxConcurrentActivityExecutionSize(200).setUsingVirtualThreads(true).build());
    w.registerActivitiesImplementations(new PaymentsActivitiesImpl(opts.apiUrl(), opts.limiterUrl(), 200, 0, null, reg, tags(id)));
    factory.start();
    activityWorker = new VWorker(id, stubs, factory, 10_000);
  }

  /** Graceful stop: stop polling, let in-flight tasks finish (bounded), then close the channel. */
  private static void stop(VWorker w) {
    try {
      w.factory().shutdown();
      w.factory().awaitTermination(w.stopTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (RuntimeException ignored) {
    } finally {
      w.stubs().shutdown();
    }
  }

  private static void stopAll(List<VWorker> ws) {
    List<Thread> ts = new ArrayList<>();
    for (VWorker w : ws) ts.add(Thread.ofVirtual().start(() -> stop(w)));
    for (Thread t : ts) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Reconciles to c. A changed shape replaces every worker (new ones first, old ones drained in
   * the background); a changed count adds workers or drains the extras in the background.
   */
  public synchronized void apply(FleetConfig c) {
    if (c.wftSlots <= 0) c.wftSlots = 200;
    if (c.laSlots <= 0) c.laSlots = 1;
    if (c.tuner == null || c.tuner.isEmpty()) c.tuner = "fixed";
    if (activityWorker == null) startActivityWorker();
    List<VWorker> retiring = new ArrayList<>();
    if (!c.sameShape(cfg) && !workers.isEmpty()) {
      System.err.println("worker settings changed, replacing workers: " + cfg + " -> " + c);
      retiring.addAll(workers);
      workers.clear();
    }
    cfg = c.copy();
    while (workers.size() < c.workers) {
      String id = opts.process() + "_w" + (workers.size() + 1);
      workers.add(startWorker(id, c));
    }
    while (workers.size() > c.workers) retiring.add(workers.remove(workers.size() - 1));
    if (!retiring.isEmpty()) Thread.ofVirtual().start(() -> stopAll(retiring));
    publish();
  }

  private void publish() {
    String p = opts.process();
    reg.dynamicGauge("demo_fleet_workers", Map.of("process", p), workers.size());
    reg.dynamicGauge("demo_worker_config", Map.of("process", p, "setting", "la_slots"), cfg.laSlots);
    reg.dynamicGauge("demo_worker_config", Map.of("process", p, "setting", "la_rate"), cfg.laRate);
    reg.dynamicGauge("demo_worker_config", Map.of("process", p, "setting", "wft_slots"), cfg.wftSlots);
    reg.dynamicGauge("demo_worker_config", Map.of("process", p, "setting", "stop_timeout_seconds"), cfg.stopTimeoutMs / 1000.0);
    reg.dynamicGauge("demo_worker_config", Map.of("process", p, "setting", "resource_tuner"), "resource".equals(cfg.tuner) ? 1 : 0);
  }

  /** GET /config response. */
  public static final class Status {
    public String process;
    public long boot;
    public FleetConfig config;
    public List<String> workers = new ArrayList<>();

    public Status() {}
  }

  public synchronized Status status() {
    Status s = new Status();
    s.process = opts.process();
    s.boot = boot;
    s.config = cfg.copy();
    for (VWorker w : workers) s.workers.add(w.id());
    s.workers.sort(String::compareTo);
    return s;
  }

  /** Drains every worker (each bounded by its stop timeout). Idempotent. */
  public void shutdownAll() {
    if (!shuttingDown.compareAndSet(false, true)) return;
    List<VWorker> ws;
    synchronized (this) {
      ws = new ArrayList<>(workers);
      if (activityWorker != null) ws.add(activityWorker);
    }
    long start = System.nanoTime();
    stopAll(ws);
    System.err.printf("graceful shutdown complete in %.1fs%n", (System.nanoTime() - start) / 1e9);
  }

  private void handleConfig(HttpExchange ex) throws IOException {
    if ("POST".equals(ex.getRequestMethod())) {
      FleetConfig c = Json.read(ex.getRequestBody(), FleetConfig.class);
      try {
        apply(c);
      } catch (RuntimeException e) {
        Http.text(ex, 500, String.valueOf(e));
        return;
      }
    }
    Http.json(ex, 200, status());
  }

  /** Control API: /healthz, /config, /crash, /shutdown. */
  public Map<String, HttpHandler> routes() {
    return Map.of(
        "/healthz", ex -> Http.text(ex, 200, "ok"),
        "/config", Http.safe(this::handleConfig),
        // Pod loss: the JVM dies immediately. In-flight workflow tasks are abandoned and only
        // retried after the workflow task timeout (10 s).
        "/crash", ex -> {
          System.err.println("crash requested: halting immediately");
          Http.send(ex, 202, "text/plain", new byte[0]);
          Thread.ofPlatform().start(() -> {
            try {
              Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(137);
          });
        },
        // Graceful: drain, then exit (Docker restarts the container).
        "/shutdown", ex -> {
          System.err.println("graceful shutdown requested");
          Http.send(ex, 202, "text/plain", new byte[0]);
          Thread.ofPlatform().start(() -> {
            shutdownAll();
            System.exit(0);
          });
        });
  }
}
