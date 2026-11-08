package lab.runner;

import java.time.Duration;
import lab.fleet.Fleet;
import lab.fleet.FleetConfig;
import lab.http.Http;

/** One worker process (a "pod" hosting several virtual workers), reached over its control API. */
public final class WorkerProc {
  public final String name;
  public final String url;
  private FleetConfig desired;
  private long boot;

  public WorkerProc(String name, String url) {
    this.name = name;
    this.url = url;
  }

  synchronized void setDesired(FleetConfig c) {
    desired = c.copy();
  }

  synchronized FleetConfig desired() {
    return desired;
  }

  void push() throws Exception {
    FleetConfig d = desired();
    if (d == null) return;
    Fleet.Status st = Http.postJson(url + "/config", d, Fleet.Status.class, Duration.ofSeconds(90));
    synchronized (this) {
      boot = st.boot;
    }
  }

  /** Re-applies the desired config whenever the process restarts (crash, graceful restart). */
  void watch() {
    while (true) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      }
      Fleet.Status st;
      try {
        st = Http.getJson(url + "/config", Fleet.Status.class, Duration.ofSeconds(60));
      } catch (Exception e) {
        continue; // down (crashed or restarting)
      }
      boolean restarted;
      synchronized (this) {
        restarted = desired != null && (st.boot != boot || st.workers.size() != desired.workers);
      }
      if (restarted) {
        System.err.println("worker process " + name + " restarted or drifted; re-applying config");
        try {
          push();
        } catch (InterruptedException e) {
          return;
        } catch (Exception e) {
          System.err.println("re-apply failed for " + name + ": " + e);
        }
      }
    }
  }

  void crash() throws Exception {
    Http.postJson(url + "/crash", null, null, Duration.ofSeconds(10));
  }

  void graceful() throws Exception {
    Http.postJson(url + "/shutdown", null, null, Duration.ofSeconds(10));
  }
}
