package lab.prom;

import com.sun.net.httpserver.HttpHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lab.http.Http;

/**
 * A tiny, dependency-free Prometheus text-format registry. Used by every component, including the
 * workers (where {@link lab.fleet.PromStatsReporter} feeds the Temporal SDK's tally metrics into
 * it), so all metric names match the Go version and the shared Grafana dashboards.
 */
public final class Registry {
  /** 5 ms .. 60 s, seconds. */
  public static final double[] LATENCY_BUCKETS = {
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 5, 8, 10, 15, 20, 30, 60
  };

  enum Kind { COUNTER, GAUGE, HISTOGRAM }

  static final class Series {
    final List<String> labels;
    double value;
    long[] buckets;
    double sum;
    long count;

    Series(List<String> labels, int nb) {
      this.labels = labels;
      if (nb > 0) buckets = new long[nb + 1];
    }
  }

  static final class Family {
    final String name, help;
    final Kind kind;
    final List<String> labelNames;
    final double[] bounds;
    final Map<String, Series> series = new TreeMap<>();

    Family(String name, String help, Kind kind, List<String> labelNames, double[] bounds) {
      this.name = name;
      this.help = help;
      this.kind = kind;
      this.labelNames = labelNames;
      this.bounds = bounds;
    }

    Series get(String... values) {
      if (values.length != labelNames.size())
        throw new IllegalArgumentException(name + ": want " + labelNames.size() + " labels, got " + values.length);
      String key = String.join("\u00ff", values);
      return series.computeIfAbsent(key, k -> new Series(List.of(values), kind == Kind.HISTOGRAM ? bounds.length : 0));
    }
  }

  private final List<Family> families = new CopyOnWriteArrayList<>();

  private Family add(Family f) {
    families.add(f);
    return f;
  }

  /** Labelled counter. */
  public static final class Counter {
    private final Family f;

    Counter(Family f) {
      this.f = f;
    }

    public void add(double v, String... labels) {
      synchronized (f) {
        f.get(labels).value += v;
      }
    }

    public void inc(String... labels) {
      add(1, labels);
    }
  }

  /** Labelled gauge. */
  public static final class Gauge {
    private final Family f;

    Gauge(Family f) {
      this.f = f;
    }

    public void set(double v, String... labels) {
      synchronized (f) {
        f.get(labels).value = v;
      }
    }

    public void add(double v, String... labels) {
      synchronized (f) {
        f.get(labels).value += v;
      }
    }

    public void reset() {
      synchronized (f) {
        f.series.clear();
      }
    }
  }

  /** Labelled histogram. */
  public static final class Histogram {
    private final Family f;

    Histogram(Family f) {
      this.f = f;
    }

    public void observe(double v, String... labels) {
      synchronized (f) {
        Series s = f.get(labels);
        int i = Arrays.binarySearch(f.bounds, v);
        if (i < 0) i = -i - 1; // first bound >= v
        s.buckets[i]++;
        s.sum += v;
        s.count++;
      }
    }
  }

  public Counter counter(String name, String help, String... labels) {
    return new Counter(add(new Family(name, help, Kind.COUNTER, List.of(labels), null)));
  }

  public Gauge gauge(String name, String help, String... labels) {
    return new Gauge(add(new Family(name, help, Kind.GAUGE, List.of(labels), null)));
  }

  public Histogram histogram(String name, String help, double[] bounds, String... labels) {
    double[] b = bounds.clone();
    Arrays.sort(b);
    return new Histogram(add(new Family(name, help, Kind.HISTOGRAM, List.of(labels), b)));
  }

  /**
   * Families created on demand with arbitrary label names (used by the tally reporter, whose tag
   * sets are only known at runtime). Keyed by name + label names.
   */
  private final Map<String, Family> dynamic = new java.util.concurrent.ConcurrentHashMap<>();

  Family dynamicFamily(String name, Kind kind, List<String> labelNames, double[] bounds) {
    String key = name + "|" + String.join(",", labelNames);
    return dynamic.computeIfAbsent(key, k -> add(new Family(name, "", kind, labelNames, bounds)));
  }

  public void dynamicCounter(String name, Map<String, String> tags, double delta) {
    Map<String, String> t = new TreeMap<>(tags);
    Family f = dynamicFamily(name, Kind.COUNTER, new ArrayList<>(t.keySet()), null);
    synchronized (f) {
      f.get(t.values().toArray(new String[0])).value += delta;
    }
  }

  public void dynamicGauge(String name, Map<String, String> tags, double value) {
    Map<String, String> t = new TreeMap<>(tags);
    Family f = dynamicFamily(name, Kind.GAUGE, new ArrayList<>(t.keySet()), null);
    synchronized (f) {
      f.get(t.values().toArray(new String[0])).value = value;
    }
  }

  public void dynamicObserve(String name, Map<String, String> tags, double v) {
    Map<String, String> t = new TreeMap<>(tags);
    Family f = dynamicFamily(name, Kind.HISTOGRAM, new ArrayList<>(t.keySet()), LATENCY_BUCKETS);
    synchronized (f) {
      Series s = f.get(t.values().toArray(new String[0]));
      int i = Arrays.binarySearch(f.bounds, v);
      if (i < 0) i = -i - 1;
      s.buckets[i]++;
      s.sum += v;
      s.count++;
    }
  }

  private static String fmt(double v) {
    if (Double.isNaN(v)) return "NaN";
    if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
    if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
    return Double.toString(v);
  }

  private static String esc(String v) {
    return v.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
  }

  private static String labels(List<String> names, List<String> values, String extraName, String extraValue) {
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) parts.add(names.get(i) + "=\"" + esc(values.get(i)) + "\"");
    if (extraName != null) parts.add(extraName + "=\"" + esc(extraValue) + "\"");
    return parts.isEmpty() ? "" : "{" + String.join(",", parts) + "}";
  }

  /** Renders the text exposition format. Families sharing a name are merged under one header. */
  public String render() {
    StringBuilder b = new StringBuilder();
    java.util.Set<String> headers = new java.util.HashSet<>();
    List<Family> sorted = new ArrayList<>(families);
    sorted.sort(java.util.Comparator.comparing(f -> f.name));
    for (Family f : sorted) {
      synchronized (f) {
        if (headers.add(f.name)) {
          String typ = switch (f.kind) {
            case COUNTER -> "counter";
            case GAUGE -> "gauge";
            case HISTOGRAM -> "histogram";
          };
          b.append("# HELP ").append(f.name).append(' ').append(f.help.isEmpty() ? f.name : f.help).append('\n');
          b.append("# TYPE ").append(f.name).append(' ').append(typ).append('\n');
        }
        for (Series s : f.series.values()) {
          if (f.kind == Kind.HISTOGRAM) {
            long cum = 0;
            for (int i = 0; i < f.bounds.length; i++) {
              cum += s.buckets[i];
              b.append(f.name).append("_bucket").append(labels(f.labelNames, s.labels, "le", fmt(f.bounds[i]))).append(' ').append(cum).append('\n');
            }
            cum += s.buckets[f.bounds.length];
            b.append(f.name).append("_bucket").append(labels(f.labelNames, s.labels, "le", "+Inf")).append(' ').append(cum).append('\n');
            b.append(f.name).append("_sum").append(labels(f.labelNames, s.labels, null, null)).append(' ').append(fmt(s.sum)).append('\n');
            b.append(f.name).append("_count").append(labels(f.labelNames, s.labels, null, null)).append(' ').append(s.count).append('\n');
          } else {
            b.append(f.name).append(labels(f.labelNames, s.labels, null, null)).append(' ').append(fmt(s.value)).append('\n');
          }
        }
      }
    }
    return b.toString();
  }

  /** HTTP handler for /metrics; before (optional) runs first, e.g. to refresh peak gauges. */
  public HttpHandler handler(Runnable before) {
    return ex -> {
      if (before != null) before.run();
      Http.send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", render().getBytes(StandardCharsets.UTF_8));
    };
  }
}
