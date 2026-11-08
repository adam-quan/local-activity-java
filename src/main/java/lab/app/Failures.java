package lab.app;

import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;

/** Error classification for activity failures. */
public final class Failures {
  private Failures() {}

  /** The ApplicationFailure type in the cause chain, "Timeout" for timeouts, or "". */
  public static String type(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ApplicationFailure af) return af.getType() == null ? "" : af.getType();
      if (c instanceof TimeoutFailure) return "Timeout";
      if (c.getCause() == c) break;
    }
    return "";
  }

  public static ApplicationFailure fail(String message, String type, boolean nonRetryable, java.time.Duration next) {
    ApplicationFailure.Builder b = ApplicationFailure.newBuilder().setMessage(message).setType(type).setNonRetryable(nonRetryable);
    if (next != null && !next.isZero()) b.setNextRetryDelay(next);
    return b.build();
  }
}
