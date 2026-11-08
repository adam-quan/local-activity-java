package lab.app;

/**
 * Per-worker rate limiter for local activities. The Java SDK has no equivalent of Go's
 * WorkerLocalActivitiesPerSecond, so the limit lives in the activity code, as the guide
 * recommends ("None; limit in code"). Burst 1, like Go's limiter.
 *
 * <p>Note the Java-specific cost: the wait happens inside the local activity, so it holds a local
 * activity slot and counts against StartToClose.
 */
public final class TokenBucket {
  private final double rate;
  private long next = System.nanoTime();

  /** rate <= 0 means unlimited. */
  public TokenBucket(double rate) {
    this.rate = rate;
  }

  public double rate() {
    return rate;
  }

  /** Waits for a token for at most maxWaitNanos. Returns false (and takes nothing) on timeout. */
  public boolean acquire(long maxWaitNanos) throws InterruptedException {
    if (rate <= 0) return true;
    long wait;
    synchronized (this) {
      long now = System.nanoTime();
      long slot = Math.max(next, now);
      wait = slot - now;
      if (wait > maxWaitNanos) return false;
      next = slot + (long) (1e9 / rate);
    }
    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
    return true;
  }
}
