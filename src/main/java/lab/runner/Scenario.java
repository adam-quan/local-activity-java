package lab.runner;

import java.util.ArrayList;
import java.util.List;

/** A runnable demonstration (one row of the guide's failure-mode table, or the sizing example). */
public final class Scenario {
  public String id;
  public String title;
  public String guide;
  public String symptom;
  public String cause;
  public String fix;
  public String watch;
  public String dashboard;
  public List<Phase> phases = new ArrayList<>();

  public Scenario() {}

  public int totalSec() {
    return phases.stream().mapToInt(p -> p.durationSec).sum();
  }
}
