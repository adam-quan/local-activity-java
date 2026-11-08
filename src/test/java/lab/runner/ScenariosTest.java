package lab.runner;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import lab.sizing.Plan;
import org.junit.Test;

public class ScenariosTest {
  @Test
  public void catalogIsConsistent() {
    for (double scale : new double[] {0.1, 0.2, 1}) {
      Plan p = Plan.of(scale);
      assertEquals(8, p.minWorkers);
      assertTrue(p.laSlotsPerWorker >= 1);
      Set<String> ids = new HashSet<>();
      for (Scenario sc : Scenarios.catalog(p)) {
        assertTrue("duplicate " + sc.id, ids.add(sc.id));
        for (Phase ph : sc.phases) {
          assertTrue(sc.id, ph.durationSec > 0 && ph.workers > 0 && !ph.streams.isEmpty());
          assertTrue(Set.of("baseline", "broken", "fixed").contains(ph.variant));
          for (Stream st : ph.streams) {
            assertTrue(st.policy.name, st.policy.name.matches("[a-z0-9_]+"));
            assertTrue(st.rate > 0 && !st.policy.steps.isEmpty());
          }
          for (Action a : ph.actions) assertTrue(a.atSec < ph.durationSec);
        }
      }
    }
  }

  @Test
  public void split() {
    assertArrayEquals(new int[] {5, 4}, Runner.split(9, 2));
    assertArrayEquals(new int[] {4, 4}, Runner.split(8, 2));
  }

  @Test
  public void historyHeartbeatDetection() {
    var t = io.temporal.api.enums.v1.EventType.class.getEnumConstants();
    java.util.List<io.temporal.api.enums.v1.EventType> evs = java.util.List.of(
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED, // heartbeat
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED);
    var m = Load.count(evs);
    assertTrue(t.length > 0);
    assertEquals(2.0, m.get("wft_completed"), 0);
    assertEquals(1.0, m.get("wft_heartbeat"), 0);
    assertEquals(1.0, m.get("marker"), 0);
    assertEquals(1.0, m.get("timer"), 0);

    // Java SDK heartbeat: the completion records finished local activity markers before the
    // forced task (seen in a live heartbeat_broken history).
    var h = Load.count(java.util.List.of(
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED, // heartbeat
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED));
    assertEquals(1.0, h.get("wft_heartbeat"), 0);
    assertEquals(3.0, h.get("marker"), 0);

    // A timer firing between tasks is not a heartbeat.
    var timer = Load.count(java.util.List.of(
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED,
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED));
    assertEquals(0.0, timer.get("wft_heartbeat"), 0);
  }
}
