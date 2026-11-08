package lab.fleet;

import static org.junit.Assert.*;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import java.util.Map;
import lab.prom.Registry;
import org.junit.Test;

/**
 * Drives the real tally-core the way the Java SDK does (names from io.temporal.worker.MetricsType)
 * and checks the Prometheus names and labels the Grafana dashboards query.
 */
public class PromStatsReporterTest {
  @Test
  public void exportsSdkMetricsWithDashboardNames() throws Exception {
    Registry reg = new Registry();
    Scope root = new RootScopeBuilder().reporter(new PromStatsReporter(reg)).tags(Map.of("process", "worker_a"))
        .reportEvery(Duration.ofMillis(100));
    Scope w = root.tagged(Map.of("worker_id", "worker_a_w1"));
    Scope la = w.tagged(Map.of("worker_type", "LocalActivityWorker", "activity_type", "CallAPI", "workflow_type", "CheckoutWorkflow"));
    la.counter("temporal_local_activity_total").inc(2);
    la.counter("temporal_local_activity_execution_failed").inc(1);
    la.timer("temporal_local_activity_execution_latency").record(Duration.ofMillis(40));
    la.timer("temporal_local_activity_succeed_endtoend_latency").record(Duration.ofMillis(90));
    la.gauge("temporal_worker_task_slots_used").update(3);
    w.tagged(Map.of("workflow_type", "CheckoutWorkflow")).timer("temporal_workflow_task_execution_latency").record(Duration.ofMillis(120));
    w.counter("temporal_sticky_cache_hit").inc(5);
    Thread.sleep(400); // counters and gauges are flushed on the report interval
    la.counter("temporal_local_activity_total").inc(3);
    Thread.sleep(400);
    String out = reg.render();

    for (String want : new String[] {
        "# TYPE temporal_local_activity_total counter",
        "# TYPE temporal_local_activity_execution_failed_total counter",
        "# TYPE temporal_local_activity_execution_latency_seconds histogram",
        "# TYPE temporal_local_activity_succeed_endtoend_latency_seconds histogram",
        "# TYPE temporal_worker_task_slots_used gauge",
        "# TYPE temporal_workflow_task_execution_latency_seconds histogram",
        "# TYPE temporal_sticky_cache_hit_total counter"}) {
      assertTrue("missing " + want + "\n" + out, out.contains(want));
    }
    String total = out.lines().filter(l -> l.startsWith("temporal_local_activity_total{")).findFirst().orElse("");
    assertTrue(total, total.contains("worker_id=\"worker_a_w1\"") && total.contains("process=\"worker_a\"")
        && total.contains("worker_type=\"LocalActivityWorker\"") && total.endsWith(" 5"));
    assertTrue(out, out.contains("temporal_local_activity_execution_latency_seconds_bucket{activity_type=\"CallAPI\",process=\"worker_a\","));
    assertTrue(out, out.contains("le=\"0.05\"} 1"));
  }
}
