package lab.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow and activity payloads. Everything a scenario varies is carried in {@link Policy}, so
 * the runner can switch between the broken and the fixed configuration without redeploying.
 * Public fields + no-arg constructors: serialised by the SDK's Jackson data converter.
 */
public final class Model {
  private Model() {}

  /** One API call in the checkout. */
  public static final class Step {
    public String name; // authorize, capture, receipt, risk, fraud, tax, ledger
    public String mode = "local"; // "local" or "regular"

    public Step() {}

    public Step(String name, String mode) {
      this.name = name;
      this.mode = mode;
    }

    public static Step local(String name) {
      return new Step(name, "local");
    }

    public Step copy() {
      return new Step(name, mode);
    }
  }

  /** RetryOptions in plain types. */
  public static final class RetrySpec {
    public int initialMs;
    public double coefficient;
    public int maxMs;
    public int maxAttempts; // 0 = unlimited
    public List<String> nonRetryable = new ArrayList<>();

    public RetrySpec() {}

    public RetrySpec(int initialMs, double coefficient, int maxMs, int maxAttempts, List<String> nonRetryable) {
      this.initialMs = initialMs;
      this.coefficient = coefficient;
      this.maxMs = maxMs;
      this.maxAttempts = maxAttempts;
      this.nonRetryable = new ArrayList<>(nonRetryable);
    }

    public RetrySpec copy() {
      return new RetrySpec(initialMs, coefficient, maxMs, maxAttempts, nonRetryable);
    }
  }

  /** Everything a scenario can vary per workflow. */
  public static final class Policy {
    public String name; // metrics label, e.g. "slowdown_broken"
    public List<Step> steps = new ArrayList<>();
    public int startToCloseMs;
    public int scheduleToCloseMs;
    public int scheduleToStartMs; // Java-only: 0 = unset (local activities support it in Java)
    public int localRetryThresholdMs; // Java-only: 0 = SDK default (3 x workflow task timeout)
    public RetrySpec retry = new RetrySpec();
    public boolean idempotency; // Idempotency-Key = workflowId:step
    public boolean circuitBreaker; // fail fast while the endpoint is failing
    public boolean fallback; // on failure, finish quickly as "deferred"
    public int checkpointEvery; // force a workflow task completion after N local calls
    public int maxRetryAfterMs; // >0: a longer Retry-After fails fast instead of waiting
    public boolean handOffLongWait; // on RetryAfterTooLong, retry the step as a regular activity
    public boolean sharedLimiter; // take a token from the fleet-wide limiter first

    public Policy() {}

    public Policy copy() {
      Policy p = new Policy();
      p.name = name;
      for (Step s : steps) p.steps.add(s.copy());
      p.startToCloseMs = startToCloseMs;
      p.scheduleToCloseMs = scheduleToCloseMs;
      p.scheduleToStartMs = scheduleToStartMs;
      p.localRetryThresholdMs = localRetryThresholdMs;
      p.retry = retry.copy();
      p.idempotency = idempotency;
      p.circuitBreaker = circuitBreaker;
      p.fallback = fallback;
      p.checkpointEvery = checkpointEvery;
      p.maxRetryAfterMs = maxRetryAfterMs;
      p.handOffLongWait = handOffLongWait;
      p.sharedLimiter = sharedLimiter;
      return p;
    }
  }

  /** Workflow input. */
  public static final class CheckoutInput {
    public String orderId;
    public int amountCents;
    public Policy policy;

    public CheckoutInput() {}

    public CheckoutInput(String orderId, int amountCents, Policy policy) {
      this.orderId = orderId;
      this.amountCents = amountCents;
      this.policy = policy;
    }
  }

  /** Workflow result. */
  public static final class CheckoutResult {
    public String status; // completed | declined | deferred | failed
    public List<String> completed = new ArrayList<>();
    public String failedAt;
    public String error;

    public CheckoutResult() {}
  }

  /** Activity input. */
  public static final class CallRequest {
    public String orderId;
    public String step;
    public int amountCents;
    public String idempotencyKey;
    public boolean circuitBreaker;
    public int maxRetryAfterMs;
    public boolean sharedLimiter;
    public String policy;

    public CallRequest() {}

    public CallRequest copy() {
      CallRequest r = new CallRequest();
      r.orderId = orderId;
      r.step = step;
      r.amountCents = amountCents;
      r.idempotencyKey = idempotencyKey;
      r.circuitBreaker = circuitBreaker;
      r.maxRetryAfterMs = maxRetryAfterMs;
      r.sharedLimiter = sharedLimiter;
      r.policy = policy;
      return r;
    }
  }

  /** Activity output. */
  public static final class CallResult {
    public String id;
    public boolean replayed;

    public CallResult() {}
  }
}
