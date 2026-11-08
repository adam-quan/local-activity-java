package lab.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import lab.Json;

/** Small helpers around the JDK HTTP server and client (no web framework needed). */
public final class Http {
  private Http() {}

  /** Starts a server on a virtual-thread-per-request executor. */
  public static HttpServer serve(int port, Map<String, HttpHandler> routes) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
    routes.forEach(server::createContext);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  public static int port(String listen) {
    String p = listen.substring(listen.lastIndexOf(':') + 1);
    return Integer.parseInt(p);
  }

  public static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    }
    ex.close();
  }

  public static void json(HttpExchange ex, int code, Object v) throws IOException {
    send(ex, code, "application/json", Json.write(v));
  }

  public static void text(HttpExchange ex, int code, String s) throws IOException {
    send(ex, code, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
  }

  public static Map<String, String> query(HttpExchange ex) {
    Map<String, String> out = new HashMap<>();
    String q = ex.getRequestURI().getRawQuery();
    if (q == null) return out;
    for (String kv : q.split("&")) {
      int i = kv.indexOf('=');
      if (i < 0) out.put(URLDecoder.decode(kv, StandardCharsets.UTF_8), "");
      else
        out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
            URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** Wraps a handler so exceptions become 500s instead of hung connections. */
  public static HttpHandler safe(HttpHandler h) {
    return ex -> {
      try {
        h.handle(ex);
      } catch (Exception e) {
        try {
          text(ex, 500, String.valueOf(e.getMessage()));
        } catch (Exception ignored) {
          ex.close();
        }
      }
    };
  }

  // ---- client
  public static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** POSTs JSON and decodes the response (out may be null). Throws on non-2xx. */
  public static <T> T postJson(String url, Object body, Class<T> out, Duration timeout)
      throws IOException, InterruptedException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Content-Type", "application/json");
    b.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(Json.write(body)));
    HttpResponse<byte[]> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() >= 300) {
      throw new IOException(url + ": HTTP " + resp.statusCode() + ": " + new String(resp.body(), StandardCharsets.UTF_8).trim());
    }
    return out == null ? null : Json.read(resp.body(), out);
  }

  public static <T> T getJson(String url, Class<T> out, Duration timeout) throws IOException, InterruptedException {
    HttpResponse<byte[]> resp =
        CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() >= 300) throw new IOException(url + ": HTTP " + resp.statusCode());
    return Json.read(resp.body(), out);
  }
}
