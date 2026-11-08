package lab.fleet;

import java.util.Objects;

/** Desired shape of one worker process's share of the fleet (same JSON as the Go version). */
public final class FleetConfig {
  public int workers; // virtual workers in this process
  public int laSlots; // setMaxConcurrentLocalActivityExecutionSize (fixed tuner)
  public double laRate; // per-worker local activity rate limit, enforced in code (0 = unlimited)
  public int wftSlots; // setMaxConcurrentWorkflowTaskExecutionSize
  public String tuner = "fixed"; // "fixed" or "resource" (resource-based local activity slots)
  public int stopTimeoutMs; // awaitTermination bound on graceful shutdown

  public FleetConfig() {}

  public FleetConfig copy() {
    FleetConfig c = new FleetConfig();
    c.workers = workers;
    c.laSlots = laSlots;
    c.laRate = laRate;
    c.wftSlots = wftSlots;
    c.tuner = tuner;
    c.stopTimeoutMs = stopTimeoutMs;
    return c;
  }

  /** Everything except the worker count: a change means the workers must be replaced. */
  boolean sameShape(FleetConfig o) {
    return o != null && laSlots == o.laSlots && Double.compare(laRate, o.laRate) == 0 && wftSlots == o.wftSlots
        && Objects.equals(tuner, o.tuner) && stopTimeoutMs == o.stopTimeoutMs;
  }

  @Override
  public String toString() {
    return "FleetConfig{workers=" + workers + ", laSlots=" + laSlots + ", laRate=" + laRate + ", wftSlots=" + wftSlots
        + ", tuner=" + tuner + ", stopTimeoutMs=" + stopTimeoutMs + "}";
  }
}
