package lab.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class TokenBucketTest {
  @Test
  public void pacesAndRespectsMaxWait() throws Exception {
    TokenBucket b = new TokenBucket(50); // one token per 20 ms, burst 1
    long start = System.nanoTime();
    int got = 0;
    for (int i = 0; i < 11; i++) if (b.acquire(1_000_000_000L)) got++;
    double el = (System.nanoTime() - start) / 1e9;
    assertEquals(11, got);
    assertTrue("elapsed " + el, el >= 0.19);
    assertFalse("no token within 1 ms right after a grant", b.acquire(1_000_000L));
    assertTrue(new TokenBucket(0).acquire(0));
  }
}
