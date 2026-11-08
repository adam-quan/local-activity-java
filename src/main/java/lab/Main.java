package lab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import lab.api.PaymentsApi;
import lab.fleet.Fleet;
import lab.fleet.FleetConfig;
import lab.http.Http;
import lab.limiter.SharedLimiter;
import lab.prom.Registry;
import lab.runner.Grafana;
import lab.runner.Runner;
import lab.runner.Scenarios;
import lab.runner.WorkerProc;
import lab.sizing.Plan;

/**
 * Local Activity Lab (Java SDK). One binary, every role:
 *
 * <pre>
 *   lab api       mock payments API (limits, latency, faults, idempotency)
 *   lab limiter   shared fleet-wide rate limiter
 *   lab worker    a worker process hosting several virtual Temporal workers
 *   lab runner    load generator, scenario engine and control page
 *   lab catalog   prints the scenario catalog as JSON (for the dashboard generator)
 * </pre>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: lab api|limiter|worker|runner|catalog");
      System.exit(2);
    }
    Plan plan = Plan.fromEnv();
    switch (args[0]) {
      case "api" -> {
        Http.serve(Http.port(Env.get("LISTEN", ":8080")), new PaymentsApi(plan).routes());
        System.err.println("payments-api listening, limits " + plan.apiRateLimit + " req/s, " + plan.apiConcurrency + " concurrent");
        new CountDownLatch(1).await();
      }
      case "limiter" -> {
        Http.serve(Http.port(Env.get("LISTEN", ":8090")), new SharedLimiter(plan.apiRateLimit).routes());
        new CountDownLatch(1).await();
      }
      case "worker" -> worker(plan);
      case "runner" -> runner(plan);
      case "catalog" -> System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Scenarios.catalog(plan)));
      default -> {
        System.err.println("unknown command " + args[0]);
        System.exit(2);
      }
    }
  }

  private static void worker(Plan plan) throws Exception {
    String process = Env.get("PROCESS_NAME", "worker_a");
    Registry reg = new Registry();
    Fleet fleet =
        new Fleet(
            new Fleet.Options(
                process,
                Env.get("TEMPORAL_ADDRESS", "localhost:7233"),
                Env.get("TEMPORAL_NAMESPACE", "default"),
                Env.get("API_URL", "http://localhost:8080"),
                Env.get("LIMITER_URL", "http://localhost:8090")),
            reg);
    Http.serve(Http.port(Env.get("METRICS_ADDR", ":9090")), java.util.Map.of("/metrics", reg.handler(null)));
    Http.serve(Http.port(Env.get("CONTROL_ADDR", ":8081")), fleet.routes());
    // SIGTERM (docker stop): drain in-flight tasks before the JVM exits.
    Runtime.getRuntime().addShutdownHook(new Thread(fleet::shutdownAll, "graceful-shutdown"));
    // Start with the guide's sized configuration; the runner re-applies whatever its phase needs.
    FleetConfig cfg = Scenarios.sizedWorker(plan);
    cfg.workers = Env.getInt("WORKERS", 0);
    try {
      fleet.apply(cfg);
    } catch (RuntimeException e) {
      System.err.println("initial apply failed: " + e);
    }
    new CountDownLatch(1).await();
  }

  private static void runner(Plan plan) throws Exception {
    List<WorkerProc> procs = new ArrayList<>();
    for (String kv : Env.get("WORKER_URLS", "worker-a=http://localhost:8081").split(",")) {
      String[] p = kv.trim().split("=", 2);
      if (p.length == 2) procs.add(new WorkerProc(p[0], p[1]));
    }
    Runner r =
        Runner.connect(
            new Runner.Options(
                plan,
                Env.get("TEMPORAL_ADDRESS", "localhost:7233"),
                Env.get("TEMPORAL_NAMESPACE", "default"),
                Env.get("API_URL", "http://localhost:8080"),
                Env.get("LIMITER_URL", "http://localhost:8090"),
                procs,
                new Grafana(Env.get("GRAFANA_URL", ""), Env.get("GRAFANA_USER", "admin"), Env.get("GRAFANA_PASSWORD", "admin")),
                Env.get("GRAFANA_PUBLIC_URL", "http://localhost:3000"),
                Env.get("TEMPORAL_UI_URL", "http://localhost:8233")));
    Http.serve(Http.port(Env.get("LISTEN", ":8088")), r.routes());
    r.start();
    new CountDownLatch(1).await();
  }
}
