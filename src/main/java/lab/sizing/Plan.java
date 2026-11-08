package lab.sizing;

import lab.Env;

/**
 * The guide's "Worked sizing example", derived from one SCALE factor so every component agrees.
 * SCALE=1 is the guide exactly (300 checkouts/s, API 1,000 req/s and 200 connections, 10 workers
 * max); smaller values shrink load and limits together so every ratio is unchanged.
 *
 * <p>Durations are nanoseconds so the JSON matches the Go version (and the shared control page).
 */
public final class Plan {
  public double scale;
  public double workflowsPerSec;
  public int callsPerWorkflow = 2;
  public double retryOverhead = 0.05;
  public double apiRateLimit;
  public int apiConcurrency;
  public int maxWorkers = 10;
  public long apiP50 = 40_000_000L;
  public long apiP99 = 150_000_000L;

  public double apiCallsPerSec;
  public int laSlotsPerWorker;
  public double laRatePerWorker;
  public int minWorkers;
  public double callsPerWorkerAtMin;
  public double peakConcurrentPerWorker;
  public double wftPerSecPerWorker;
  public double concurrentWftPerWorker;
  public int wftSlotsPerWorker = 200;
  public long startToClose = 500_000_000L;
  public long scheduleToClose = 2_000_000_000L;
  public long worstCaseWft;
  public long heartbeatPoint = 8_000_000_000L;
  public int httpPoolPerWorker;
  public double headroomUtilization = 0.8;
  public double fleetRateCapAtMin;
  public double fleetRateCapBelowMin;
  public int fleetConcurrencyCapAtMax;

  public static Plan fromEnv() {
    double s;
    try {
      s = Double.parseDouble(Env.get("SCALE", "0.2"));
    } catch (NumberFormatException e) {
      s = 0.2;
    }
    return of(s <= 0 ? 0.2 : s);
  }

  public static Plan of(double scale) {
    Plan p = new Plan();
    p.scale = scale;
    p.workflowsPerSec = 300 * scale;
    p.apiRateLimit = 1000 * scale;
    p.apiConcurrency = Math.max(p.maxWorkers, (int) Math.round(200 * scale));
    p.apiCallsPerSec = p.workflowsPerSec * p.callsPerWorkflow * (1 + p.retryOverhead);
    p.laSlotsPerWorker = p.apiConcurrency / p.maxWorkers;
    p.laRatePerWorker = p.apiRateLimit / p.maxWorkers;
    // "630 / 100, rounded up with headroom": keep each worker's limiter at <= 80% utilisation.
    p.minWorkers = (int) Math.ceil(p.apiCallsPerSec / (p.laRatePerWorker * p.headroomUtilization));
    p.callsPerWorkerAtMin = p.apiCallsPerSec / p.minWorkers;
    p.peakConcurrentPerWorker = p.callsPerWorkerAtMin * p.apiP99 / 1e9;
    p.wftPerSecPerWorker = p.workflowsPerSec * 2 / p.minWorkers;
    p.concurrentWftPerWorker = p.wftPerSecPerWorker * 0.3;
    p.worstCaseWft = p.callsPerWorkflow * p.scheduleToClose;
    p.httpPoolPerWorker = p.laSlotsPerWorker;
    p.fleetRateCapAtMin = p.minWorkers * p.laRatePerWorker;
    p.fleetRateCapBelowMin = (p.minWorkers - 2) * p.laRatePerWorker;
    p.fleetConcurrencyCapAtMax = p.maxWorkers * p.laSlotsPerWorker;
    return p;
  }

  public double startToCloseSeconds() {
    return startToClose / 1e9;
  }

  public double scheduleToCloseSeconds() {
    return scheduleToClose / 1e9;
  }
}
