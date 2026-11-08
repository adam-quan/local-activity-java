package lab;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** JSON for the lab's own HTTP APIs: snake_case, same wire format as the Go version. */
public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {}

  public static byte[] write(Object v) {
    try {
      return MAPPER.writeValueAsBytes(v);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String string(Object v) {
    return new String(write(v), java.nio.charset.StandardCharsets.UTF_8);
  }

  public static <T> T read(InputStream in, Class<T> type) throws IOException {
    return MAPPER.readValue(in, type);
  }

  public static <T> T read(byte[] in, Class<T> type) throws IOException {
    return MAPPER.readValue(in, type);
  }
}
