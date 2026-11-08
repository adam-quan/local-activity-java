package lab.app;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import lab.TestHttp;
import lab.api.PaymentsApi;
import lab.app.Model.CheckoutInput;
import lab.app.Model.CheckoutResult;
import lab.app.Model.Policy;
import lab.app.Model.RetrySpec;
import lab.app.Model.Step;
import lab.sizing.Plan;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The checkout workflow in the SDK's in-memory test environment against the real mock API. */
public class CheckoutWorkflowTest {
  private HttpServer api;
  private String apiUrl;
  private TestWorkflowEnvironment env;
  private WorkflowClient client;

  @Before
  public void setUp() throws Exception {
    api = TestHttp.serve(new PaymentsApi(Plan.of(1)).routes());
    apiUrl = TestHttp.url(api);
    env = TestWorkflowEnvironment.newInstance();
    Worker w = env.newWorker(Names.TASK_QUEUE);
    Worker activities = env.newWorker(Names.ACTIVITY_TASK_QUEUE);
    PaymentsActivitiesImpl impl = new PaymentsActivitiesImpl(apiUrl, null, 8, 0, new Breakers(null), null, Map.of());
    w.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
    w.registerActivitiesImplementations(impl);
    activities.registerActivitiesImplementations(impl);
    env.start();
    client = env.getWorkflowClient();
  }

  @After
  public void tearDown() {
    env.close();
    api.stop(0);
  }

  static Policy sized() {
    Policy p = new Policy();
    p.name = "test";
    p.steps = new java.util.ArrayList<>(List.of(Step.local("authorize"), Step.local("capture")));
    p.startToCloseMs = 500;
    p.scheduleToCloseMs = 2000;
    p.retry = new RetrySpec(100, 2, 500, 4, List.of(Names.ERR_INVALID_REQUEST, Names.ERR_CIRCUIT_OPEN, Names.ERR_RETRY_AFTER_TOO_LONG));
    p.idempotency = true;
    return p;
  }

  private CheckoutResult run(Policy p, String order) {
    CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class,
        WorkflowOptions.newBuilder().setWorkflowId(order).setTaskQueue(Names.TASK_QUEUE).build());
    return wf.run(new CheckoutInput(order, 100, p));
  }

  private void faults(String json) throws Exception {
    TestHttp.post(apiUrl + "/admin/faults", json, Map.of());
  }

  @Test
  public void happyPath() {
    CheckoutResult r = run(sized(), "order-1");
    assertEquals("completed", r.status);
    assertEquals(List.of("authorize", "capture"), r.completed);
  }

  @Test
  public void invalidCardIsDeclinedWithoutRetries() throws Exception {
    faults("{\"invalid_rate\":1}");
    CheckoutResult r = run(sized(), "order-2");
    assertEquals("declined", r.status);
    assertEquals("authorize", r.failedAt);
  }

  @Test
  public void outageWithFallbackIsDeferred() throws Exception {
    faults("{\"outage\":true}");
    Policy p = sized();
    p.fallback = true;
    CheckoutResult r = run(p, "order-3");
    assertEquals("deferred", r.status);
    assertEquals(Names.ERR_UNAVAILABLE, r.error);
  }

  @Test
  public void checkpointsComplete() {
    Policy p = sized();
    p.steps = new java.util.ArrayList<>(List.of(Step.local("authorize"), Step.local("fraud"), Step.local("tax"), Step.local("capture"), Step.local("ledger")));
    p.checkpointEvery = 2;
    assertEquals("completed", run(p, "order-5").status);
  }
}
