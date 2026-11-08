package lab.runner;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lab.app.Model.CheckoutInput;
import lab.app.Model.CheckoutResult;
import lab.app.Names;
import lab.prom.Registry;

/** Drives the streams (open loop) and records what each workflow experienced. */
public final class Load {
  private final List<WorkflowClient> clients;
  private final AtomicLong next = new AtomicLong();
  private final AtomicLong seq = new AtomicLong();
  private final String prefix = "co-" + (System.currentTimeMillis() / 1000 % 100000);
  private final Semaphore sem = new Semaphore(20_000);
  private final AtomicLong inflight = new AtomicLong();

  private List<Stream> streams = List.of();
  private double[] accum = new double[0];

  private final Registry.Counter started, results, sampled, events, dropped;
  private final Registry.Histogram e2e, queryLat, perWf;
  private final Registry.Gauge target, gInflight;

  public Load(List<WorkflowClient> clients, Registry reg) {
    this.clients = clients;
    started = reg.counter("demo_workflows_started_total", "Workflows started by the load generator.", "policy");
    results = reg.counter("demo_workflow_results_total",
        "Workflow outcomes: completed, declined (invalid card), deferred (fallback), failed, error, start_error.", "policy", "status");
    e2e = reg.histogram("demo_workflow_e2e_seconds", "Start request to result, as the caller sees it.", Registry.LATENCY_BUCKETS, "policy");
    queryLat = reg.histogram("demo_query_latency_seconds",
        "Latency of a status query sent 300 ms after start (waits while a workflow task is open).", Registry.LATENCY_BUCKETS, "policy");
    sampled = reg.counter("demo_history_sampled_total", "Completed workflows whose history was analysed.", "policy");
    events = reg.counter("demo_history_events_total",
        "Events in sampled histories: wft_completed, wft_heartbeat, wft_timed_out, wft_failed, marker, timer, activity_scheduled.", "policy", "kind");
    perWf = reg.histogram("demo_history_wft_per_workflow", "Workflow tasks completed per sampled workflow.",
        new double[] {1, 2, 3, 4, 5, 6, 8, 10, 15, 20}, "policy");
    target = reg.gauge("demo_load_target_wfps", "Target workflow start rate per policy.", "policy");
    gInflight = reg.gauge("demo_inflight_workflows", "Workflows started and not yet finished (as seen by the runner).");
    dropped = reg.counter("demo_workflows_dropped_total", "Starts skipped because the runner hit its in-flight cap.", "policy");
  }

  /** Replaces the running streams. */
  public synchronized void setStreams(List<Stream> s) {
    target.reset();
    streams = s == null ? List.of() : List.copyOf(s);
    accum = new double[streams.size()];
    for (Stream st : streams) target.add(st.rate, st.policy.name);
  }

  synchronized List<Stream> streams() {
    return streams;
  }

  /** Starts the 20 ms scheduling loop on a platform thread. */
  public void start() {
    Thread t = new Thread(this::loop, "load");
    t.setDaemon(true);
    t.start();
  }

  private void loop() {
    long last = System.nanoTime();
    while (true) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        return;
      }
      long now = System.nanoTime();
      double dt = (now - last) / 1e9;
      last = now;
      List<Stream> todo = new ArrayList<>();
      synchronized (this) {
        for (int i = 0; i < streams.size(); i++) {
          accum[i] += streams.get(i).rate * dt;
          while (accum[i] >= 1) {
            accum[i] -= 1;
            todo.add(streams.get(i));
          }
        }
      }
      for (Stream s : todo) {
        if (sem.tryAcquire()) Thread.ofVirtual().start(() -> one(s));
        else dropped.inc(s.policy.name);
      }
      gInflight.set(inflight.get());
    }
  }

  private WorkflowClient client() {
    return clients.get((int) (next.getAndIncrement() % clients.size()));
  }

  private void one(Stream s) {
    inflight.incrementAndGet();
    try {
      run(s);
    } finally {
      inflight.decrementAndGet();
      sem.release();
    }
  }

  private void run(Stream s) {
    WorkflowClient c = client();
    String policy = s.policy.name;
    String id = prefix + "-" + policy.replace('_', '-') + "-" + seq.incrementAndGet();
    WorkflowStub stub =
        c.newUntypedWorkflowStub(
            Names.WORKFLOW_NAME,
            WorkflowOptions.newBuilder()
                .setWorkflowId(id)
                .setTaskQueue(Names.TASK_QUEUE)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                .build());
    long start = System.nanoTime();
    WorkflowExecution exec;
    try {
      exec = stub.start(new CheckoutInput(id, 1000 + ThreadLocalRandom.current().nextInt(9000), s.policy));
    } catch (RuntimeException e) {
      results.inc(policy, "start_error");
      return;
    }
    started.inc(policy);

    if (s.queryRate > 0 && ThreadLocalRandom.current().nextDouble() < s.queryRate) {
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
          long qs = System.nanoTime();
          stub.query(Names.QUERY_STATUS, String.class);
          queryLat.observe((System.nanoTime() - qs) / 1e9, policy);
        } catch (Exception ignored) {
          // query failures are not measured
        }
      });
    }

    CheckoutResult res;
    try {
      res = stub.getResultAsync(CheckoutResult.class).get(10, TimeUnit.MINUTES);
    } catch (Exception e) {
      results.inc(policy, "error");
      return;
    }
    e2e.observe((System.nanoTime() - start) / 1e9, policy);
    results.inc(policy, res.status == null ? "error" : res.status);

    if (s.historySample > 0 && ThreadLocalRandom.current().nextDouble() < s.historySample) {
      analyse(c, id, exec.getRunId(), policy);
    }
  }

  /**
   * Counts the events that matter for local activities. A workflow task heartbeat shows up as
   * WorkflowTaskCompleted followed by WorkflowTaskScheduled with nothing but markers in between:
   * the Java SDK's heartbeat completion also records the markers of local activities that have
   * finished so far, so the forced task comes after them.
   */
  void analyse(WorkflowClient c, String id, String runId, String policy) {
    List<HistoryEvent> evs;
    try {
      evs = c.fetchHistory(id, runId).getEvents();
    } catch (RuntimeException e) {
      return;
    }
    Map<String, Double> counts = count(evs.stream().map(HistoryEvent::getEventType).toList());
    sampled.inc(policy);
    counts.forEach((k, v) -> events.add(v, policy, k));
    perWf.observe(counts.get("wft_completed"), policy);
  }

  /** Pure function, unit-tested. */
  static Map<String, Double> count(List<EventType> types) {
    Map<String, Double> m = new LinkedHashMap<>();
    for (String k : List.of("wft_completed", "wft_heartbeat", "wft_timed_out", "wft_failed", "marker", "timer", "activity_scheduled")) m.put(k, 0.0);
    EventType prev = EventType.EVENT_TYPE_UNSPECIFIED;
    for (EventType t : types) {
      switch (t) {
        case EVENT_TYPE_WORKFLOW_TASK_COMPLETED -> m.merge("wft_completed", 1.0, Double::sum);
        case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT -> m.merge("wft_timed_out", 1.0, Double::sum);
        case EVENT_TYPE_WORKFLOW_TASK_FAILED -> m.merge("wft_failed", 1.0, Double::sum);
        case EVENT_TYPE_MARKER_RECORDED -> m.merge("marker", 1.0, Double::sum);
        case EVENT_TYPE_TIMER_STARTED -> m.merge("timer", 1.0, Double::sum);
        case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> m.merge("activity_scheduled", 1.0, Double::sum);
        case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED -> {
          if (prev == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) m.merge("wft_heartbeat", 1.0, Double::sum);
        }
        default -> {}
      }
      if (t != EventType.EVENT_TYPE_MARKER_RECORDED) prev = t;
    }
    return m;
  }
}
