package lab.runner;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lab.Json;
import lab.http.Http;

/** Posts region annotations so every Grafana panel shows the scenario phases. */
public final class Grafana {
  private final String url, auth;

  public Grafana(String url, String user, String password) {
    this.url = url == null ? "" : url;
    this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private Map<?, ?> send(String method, String path, Object body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url + path))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .header("Authorization", auth)
        .method(method, HttpRequest.BodyPublishers.ofByteArray(Json.write(body)))
        .build();
    HttpResponse<byte[]> resp = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() >= 300) throw new IllegalStateException("grafana " + method + " " + path + ": HTTP " + resp.statusCode());
    return Json.read(resp.body(), Map.class);
  }

  /** Creates a region annotation and returns its id (0 on failure). */
  public long start(String text, List<String> tags) {
    if (url.isEmpty()) return 0;
    long now = System.currentTimeMillis();
    try {
      Object id = send("POST", "/api/annotations", Map.of("time", now, "timeEnd", now, "tags", tags, "text", text)).get("id");
      return id instanceof Number n ? n.longValue() : 0;
    } catch (Exception e) {
      System.err.println("annotation failed: " + e.getMessage());
      return 0;
    }
  }

  /** Sets the region's end time. */
  public void end(long id) {
    if (id == 0 || url.isEmpty()) return;
    try {
      send("PATCH", "/api/annotations/" + id, Map.of("timeEnd", System.currentTimeMillis()));
    } catch (Exception e) {
      System.err.println("annotation end failed: " + e.getMessage());
    }
  }

  /** Creates an instant annotation (e.g. "worker-a crashed"). */
  public void point(String text, List<String> tags) {
    if (url.isEmpty()) return;
    try {
      send("POST", "/api/annotations", Map.of("time", System.currentTimeMillis(), "tags", tags, "text", text));
    } catch (Exception e) {
      System.err.println("annotation failed: " + e.getMessage());
    }
  }
}
