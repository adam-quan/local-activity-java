package lab.app;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import lab.app.Model.CheckoutInput;
import lab.app.Model.CheckoutResult;

@WorkflowInterface
public interface CheckoutWorkflow {
  @WorkflowMethod(name = Names.WORKFLOW_NAME)
  CheckoutResult run(CheckoutInput input);

  /** Used by the runner to measure query latency (queries wait while a workflow task is open). */
  @QueryMethod(name = Names.QUERY_STATUS)
  String status();
}
