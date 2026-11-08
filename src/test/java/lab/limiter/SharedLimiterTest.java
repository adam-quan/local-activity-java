package lab.limiter;

import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lab.TestHttp;
import org.junit.Test;

public class SharedLimiterTest {
  @Test
  public void fleetWideRate() throws Exception {
    HttpServer s = TestHttp.serve(new SharedLimiter(100).routes());
    String u = TestHttp.url(s);
    try {
      AtomicInteger ok = new AtomicInteger();
      List<Thread> ts = new ArrayList<>();
      long start = System.nanoTime();
      for (int w = 0; w < 10; w++) {
        ts.add(Thread.ofVirtual().start(() -> {
          for (int i = 0; i < 40; i++) {
            try {
              if (TestHttp.get(u + "/acquire?key=payments&max_wait_ms=200").statusCode() == 200) ok.incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        }));
      }
      for (Thread t : ts) t.join();
      double rate = ok.get() / ((System.nanoTime() - start) / 1e9);
      assertTrue("granted " + rate + "/s", rate <= 115);
    } finally {
      s.stop(0);
    }
  }
}
