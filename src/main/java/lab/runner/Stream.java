package lab.runner;

import lab.app.Model.Policy;

/** One open-loop source of workflow starts. */
public final class Stream {
  public double rate; // workflow starts per second
  public Policy policy; // policy.name is the metrics label
  public double queryRate; // share of workflows probed with a status query 300 ms after start
  public double historySample; // share of completed workflows whose history is analysed

  public Stream() {}

  public Stream(double rate, Policy policy, double queryRate, double historySample) {
    this.rate = rate;
    this.policy = policy;
    this.queryRate = queryRate;
    this.historySample = historySample;
  }
}
