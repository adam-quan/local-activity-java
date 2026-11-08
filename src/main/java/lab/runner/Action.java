package lab.runner;

/** Something that happens part-way through a phase. */
public final class Action {
  public int atSec;
  public String kind; // crash | graceful
  public String target; // worker process name (worker-a)

  public Action() {}

  public Action(int atSec, String kind, String target) {
    this.atSec = atSec;
    this.kind = kind;
    this.target = target;
  }
}
