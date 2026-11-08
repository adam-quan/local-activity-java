package lab.app;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import lab.app.Model.CallRequest;
import lab.app.Model.CallResult;
import lab.app.Model.CheckoutInput;
import lab.app.Model.CheckoutResult;
import lab.app.Model.Policy;
import lab.app.Model.RetrySpec;
import lab.app.Model.Step;

/**
 * Runs the policy's steps in order. Local steps run as local activities (inside the workflow
 * task); regular steps go through the activity task queue.
 */
public class CheckoutWorkflowImpl implements CheckoutWorkflow {
  private String status = "running";
  private String current = "";

  static RetryOptions retryOptions(RetrySpec r) {
    if (r == null || r.initialMs == 0) return null; // SDK default
    RetryOptions.Builder b =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(r.initialMs))
            .setBackoffCoefficient(Math.max(1, r.coefficient))
            .setMaximumInterval(Duration.ofMillis(Math.max(r.maxMs, r.initialMs)))
            .setMaximumAttempts(r.maxAttempts);
    if (r.nonRetryable != null && !r.nonRetryable.isEmpty()) b.setDoNotRetry(r.nonRetryable.toArray(new String[0]));
    return b.build();
  }

  static LocalActivityOptions localOptions(Policy p) {
    LocalActivityOptions.Builder b =
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMillis(p.startToCloseMs))
            .setScheduleToCloseTimeout(Duration.ofMillis(p.scheduleToCloseMs));
    if (p.scheduleToStartMs > 0) b.setScheduleToStartTimeout(Duration.ofMillis(p.scheduleToStartMs));
    if (p.localRetryThresholdMs > 0) b.setLocalRetryThreshold(Duration.ofMillis(p.localRetryThresholdMs));
    RetryOptions ro = retryOptions(p.retry);
    if (ro != null) b.setRetryOptions(ro);
    return b.build();
  }

  static ActivityOptions regularOptions() {
    return ActivityOptions.newBuilder()
        .setTaskQueue(Names.ACTIVITY_TASK_QUEUE)
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setScheduleToCloseTimeout(Duration.ofMinutes(5))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2)
                .setMaximumInterval(Duration.ofSeconds(30))
                .setDoNotRetry(Names.ERR_INVALID_REQUEST)
                .build())
        .build();
  }

  @Override
  public String status() {
    return status + ":" + current;
  }

  @Override
  public CheckoutResult run(CheckoutInput in) {
    Policy p = in.policy;
    CheckoutResult res = new CheckoutResult();
    ActivityStub local = Workflow.newUntypedLocalActivityStub(localOptions(p));
    ActivityStub regular = Workflow.newUntypedActivityStub(regularOptions());
    String workflowId = Workflow.getInfo().getWorkflowId();

    int localSinceCheckpoint = 0;
    for (int i = 0; i < p.steps.size(); i++) {
      Step step = p.steps.get(i);
      current = step.name;
      CallRequest req = new CallRequest();
      req.orderId = in.orderId;
      req.step = step.name;
      req.amountCents = in.amountCents;
      req.circuitBreaker = p.circuitBreaker;
      req.maxRetryAfterMs = p.maxRetryAfterMs;
      req.sharedLimiter = p.sharedLimiter;
      req.policy = p.name;
      if (p.idempotency) {
        // Stable across workflow task retries, worker crashes and replays.
        req.idempotencyKey = workflowId + ":" + step.name;
      }

      try {
        if ("regular".equals(step.mode)) {
          regular.execute(Names.ACTIVITY_NAME, CallResult.class, asRegular(req));
        } else {
          try {
            local.execute(Names.ACTIVITY_NAME, CallResult.class, req);
          } catch (RuntimeException e) {
            if (!(p.handOffLongWait && Names.ERR_RETRY_AFTER_TOO_LONG.equals(Failures.type(e)))) throw e;
            // The API asked us to wait longer than a local activity should: hand this step to a
            // regular activity, which waits server-side without timers or extra workflow tasks.
            regular.execute(Names.ACTIVITY_NAME, CallResult.class, asRegular(req));
          }
          localSinceCheckpoint++;
        }
      } catch (RuntimeException e) {
        res.failedAt = step.name;
        res.error = Failures.type(e);
        if (Names.ERR_INVALID_REQUEST.equals(res.error)) res.status = "declined";
        else if (p.fallback) res.status = "deferred"; // e.g. queued for later; the caller gets a fast answer
        else res.status = "failed";
        status = res.status;
        current = "";
        return res;
      }
      res.completed.add(step.name);

      if (p.checkpointEvery > 0 && localSinceCheckpoint >= p.checkpointEvery && i < p.steps.size() - 1) {
        // A 1 ms timer forces the workflow task to complete, persisting the markers of the local
        // activities run so far. A crash after this point no longer re-runs them.
        Workflow.sleep(Duration.ofMillis(1));
        localSinceCheckpoint = 0;
      }
    }
    res.status = "completed";
    status = res.status;
    current = "";
    return res;
  }

  private static CallRequest asRegular(CallRequest req) {
    CallRequest r = req.copy();
    r.maxRetryAfterMs = 0; // a regular activity can wait out any Retry-After server-side
    r.circuitBreaker = false;
    return r;
  }
}
