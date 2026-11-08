package lab.app;

/** Task queues, registered names and error types (identical to the Go version). */
public final class Names {
  private Names() {}

  /** Polled by the workflow workers (workflow tasks + local activities). */
  public static final String TASK_QUEUE = "checkout";
  /** Polled by the activity-only workers (regular activities). */
  public static final String ACTIVITY_TASK_QUEUE = "checkout-activities";
  public static final String ACTIVITY_NAME = "CallAPI";
  public static final String WORKFLOW_NAME = "CheckoutWorkflow";
  public static final String QUERY_STATUS = "status";

  public static final String ERR_INVALID_REQUEST = "InvalidRequest"; // 400/401/403/404/409/422
  public static final String ERR_RATE_LIMITED = "RateLimited"; // 429
  public static final String ERR_UNAVAILABLE = "Unavailable"; // 5xx, network errors, client timeouts
  public static final String ERR_CIRCUIT_OPEN = "CircuitOpen"; // breaker open, no call made
  public static final String ERR_RETRY_AFTER_TOO_LONG = "RetryAfterTooLong"; // Retry-After exceeds budget
  public static final String ERR_LIMITER_TIMEOUT = "LimiterTimeout"; // no rate-limit token in time
}
