package lab.runner;

import java.util.ArrayList;
import java.util.List;
import lab.api.PaymentsApi.Faults;
import lab.fleet.FleetConfig;

/** One step of a scenario. */
public final class Phase {
  public String name;
  public String variant; // baseline | broken | fixed
  public String note = "";
  public int durationSec;
  public int workers; // total virtual workers across processes
  public FleetConfig worker; // per-worker settings (workers field ignored)
  public Faults faults;
  public double limiter; // shared limiter rate (0 = API rate)
  public List<Stream> streams = new ArrayList<>();
  public List<Action> actions = new ArrayList<>();

  public Phase() {}
}
