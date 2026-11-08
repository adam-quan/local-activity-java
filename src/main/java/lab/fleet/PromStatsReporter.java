package lab.fleet;

import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import java.util.Map;
import lab.prom.Registry;

/**
 * Tally reporter that exports the Java SDK's metrics in the same Prometheus shape as the Go SDK's
 * tally reporter, so both labs share one set of Grafana dashboards:
 *
 * <ul>
 *   <li>timers become {@code <name>_seconds} histograms (buckets 5 ms .. 60 s);
 *   <li>counters get a {@code _total} suffix (tally reports deltas, which are accumulated here);
 *   <li>gauges keep their name.
 * </ul>
 *
 * Production alternative: the SDK's MicrometerClientStatsReporter with a Prometheus registry
 * (enable percentile histograms on timers to get {@code _bucket} series).
 */
@SuppressWarnings({"rawtypes", "deprecation"})
public final class PromStatsReporter implements StatsReporter {
  private final Registry reg;

  public PromStatsReporter(Registry reg) {
    this.reg = reg;
  }

  static String counterName(String name) {
    return name.endsWith("_total") ? name : name + "_total";
  }

  static String timerName(String name) {
    return name.endsWith("_seconds") ? name : name + "_seconds";
  }

  private static Map<String, String> t(Map<String, String> tags) {
    return tags == null ? Map.of() : tags;
  }

  @Override
  public void reportCounter(String name, Map<String, String> tags, long value) {
    reg.dynamicCounter(counterName(name), t(tags), value);
  }

  @Override
  public void reportGauge(String name, Map<String, String> tags, double value) {
    reg.dynamicGauge(name, t(tags), value);
  }

  @Override
  public void reportTimer(String name, Map<String, String> tags, Duration interval) {
    reg.dynamicObserve(timerName(name), t(tags), interval.getSeconds());
  }

  @Override
  public void reportHistogramValueSamples(
      String name, Map<String, String> tags, Buckets buckets, double lower, double upper, long samples) {
    double v = Double.isInfinite(upper) ? lower : upper;
    for (long i = 0; i < samples; i++) reg.dynamicObserve(name, t(tags), v);
  }

  @Override
  public void reportHistogramDurationSamples(
      String name, Map<String, String> tags, Buckets buckets, Duration lower, Duration upper, long samples) {
    double v = upper.getSeconds();
    if (Double.isInfinite(v) || v > 1e9) v = lower.getSeconds();
    for (long i = 0; i < samples; i++) reg.dynamicObserve(timerName(name), t(tags), v);
  }

  @Override
  public Capabilities capabilities() {
    return CapableOf.REPORTING_TAGGING;
  }

  @Override
  public void flush() {}

  @Override
  public void close() {}
}
