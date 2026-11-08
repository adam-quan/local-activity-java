package lab.app;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import lab.TestHttp;
import lab.api.PaymentsApi;
import lab.app.Model.CallRequest;
import lab.app.Model.CallResult;
import lab.prom.Registry;
import lab.sizing.Plan;
import org.junit.Test;

/** CallAPI's success path and metrics against the real mock API (outside Temporal). */
public class ActivitiesTest {
  @Test
  public void callsApiWithIdempotencyKey() throws Exception {
    HttpServer s = TestHttp.serve(new PaymentsApi(Plan.of(1)).routes());
    String u = TestHttp.url(s);
    try {
      Registry reg = new Registry();
      PaymentsActivitiesImpl a = new PaymentsActivitiesImpl(u, null, 4, 0, new Breakers(null), reg,
          java.util.Map.of("process", "worker_a", "worker_id", "worker_a_w1"));
      CallRequest req = new CallRequest();
      req.orderId = "a1";
      req.step = "authorize";
      req.idempotencyKey = "a1:authorize";
      req.policy = "test";
      CallResult first = a.callApi(req);
      CallResult again = a.callApi(req);
      assertFalse(first.replayed);
      assertTrue(again.replayed);
      assertEquals(first.id, again.id);
      String out = reg.render();
      assertTrue(out, out.contains("demo_api_calls_total{outcome=\"ok\",policy=\"test\",process=\"worker_a\",step=\"authorize\",worker_id=\"worker_a_w1\"} 1"));
      assertTrue(out, out.contains("demo_api_call_latency_seconds_bucket{"));
    } finally {
      s.stop(0);
    }
  }
}
