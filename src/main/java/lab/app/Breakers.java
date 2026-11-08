package lab.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Per-endpoint circuit breakers shared by all workers in a process. A breaker opens when >= 50%
 * of at least 20 calls in a 2 s window fail, stays open 3 s, then lets one probe through every
 * 500 ms until a probe succeeds.
 */
public final class Breakers {
  static final long WINDOW = 2_000_000_000L, OPEN_FOR = 3_000_000_000L, PROBE_GAP = 500_000_000L;
  static final int MIN_CALLS = 20;
  static final double RATIO = 0.5;

  private final Map<String, Breaker> m = new ConcurrentHashMap<>();
  private final BiConsumer<String, Double> gauge; // (step, state) -> metric, may be null

  public Breakers(BiConsumer<String, Double> gauge) {
    this.gauge = gauge;
  }

  public Breaker get(String step) {
    return m.computeIfAbsent(step, s -> new Breaker(s));
  }

  /** One endpoint's breaker. */
  public final class Breaker {
    private final String step;
    private long windowStart = System.nanoTime(), openUntil, lastProbe;
    private int total, fail;
    private boolean halfOpen;

    Breaker(String step) {
      this.step = step;
    }

    private void set(double v) {
      if (gauge != null) gauge.accept(step, v);
    }

    public synchronized boolean allow() {
      long now = System.nanoTime();
      if (now < openUntil) return false;
      if (halfOpen) {
        if (now - lastProbe < PROBE_GAP) return false;
        lastProbe = now;
        set(0.5);
      }
      return true;
    }

    public synchronized void record(boolean ok) {
      long now = System.nanoTime();
      if (halfOpen) {
        if (ok) {
          halfOpen = false;
          total = fail = 0;
          windowStart = now;
          set(0);
        } else {
          openUntil = now + OPEN_FOR;
          set(1);
        }
        return;
      }
      if (now - windowStart > WINDOW) {
        windowStart = now;
        total = fail = 0;
      }
      total++;
      if (!ok) fail++;
      if (total >= MIN_CALLS && (double) fail / total >= RATIO) {
        openUntil = now + OPEN_FOR;
        halfOpen = true;
        lastProbe = now;
        set(1);
      }
    }
  }
}
