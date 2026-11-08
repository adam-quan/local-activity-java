package lab.app;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import lab.app.Model.CallRequest;
import lab.app.Model.CallResult;

@ActivityInterface
public interface PaymentsActivities {
  /** Used both as a local activity and as a regular activity. */
  @ActivityMethod(name = Names.ACTIVITY_NAME)
  CallResult callApi(CallRequest request);
}
