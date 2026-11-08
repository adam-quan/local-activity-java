package lab.runner;

/** What the control page shows (same JSON as the Go version). */
public final class State {
  public String scenario = "idle";
  public String phase = "";
  public String variant = "baseline";
  public String note = "";
  public int phaseIndex;
  public int phases;
  public String phaseEnds; // ISO-8601
  public String started; // ISO-8601
  public boolean paused;
  public String lastError;

  public State() {}

  public State copy() {
    State s = new State();
    s.scenario = scenario;
    s.phase = phase;
    s.variant = variant;
    s.note = note;
    s.phaseIndex = phaseIndex;
    s.phases = phases;
    s.phaseEnds = phaseEnds;
    s.started = started;
    s.paused = paused;
    s.lastError = lastError;
    return s;
  }
}
