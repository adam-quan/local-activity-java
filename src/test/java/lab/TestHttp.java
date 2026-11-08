package lab;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import lab.http.Http;

/** Test helpers: ephemeral servers and a simple client. */
public final class TestHttp {
  private TestHttp() {}

  public static HttpServer serve(Map<String, HttpHandler> routes) throws Exception {
    return Http.serve(0, routes);
  }

  public static String url(HttpServer s) {
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  public static final HttpClient C = HttpClient.newHttpClient();

  public static HttpResponse<String> post(String url, String body, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    return C.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  public static HttpResponse<String> get(String url) throws Exception {
    return C.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  public static String metric(String base, String line) throws Exception {
    for (String l : get(base + "/metrics").body().split("\n")) if (l.startsWith(line + " ")) return l.substring(line.length() + 1);
    return null;
  }
}
