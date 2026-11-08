package lab.app;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;

public class BreakersTest {
  @Test
  public void opensAfterFailuresAndProbes() throws Exception {
    Map<String, Double> gauge = new ConcurrentHashMap<>();
    Breakers b = new Breakers(gauge::put);
    Breakers.Breaker br = b.get("authorize");
    for (int i = 0; i < 25; i++) {
      assertTrue(br.allow());
      br.record(false);
      if (!br.allow()) break;
    }
    assertFalse("breaker should be open", br.allow());
    assertEquals(1.0, gauge.get("authorize"), 0);
  }

  @Test
  public void staysClosedOnSuccess() {
    Breakers.Breaker br = new Breakers(null).get("capture");
    for (int i = 0; i < 100; i++) {
      assertTrue(br.allow());
      br.record(i % 3 != 0); // 33% failures < 50%
    }
    assertTrue(br.allow());
  }
}
