package lab;

/** Environment variable helpers. */
public final class Env {
  private Env() {}

  public static String get(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isEmpty() ? def : v;
  }

  public static int getInt(String key, int def) {
    try {
      return Integer.parseInt(get(key, Integer.toString(def)));
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
