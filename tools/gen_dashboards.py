#!/usr/bin/env python3
"""Generate the Grafana dashboards for the Local Activity Lab.

    build/install/lab/bin/lab catalog > tools/catalog.json   # scenario text (symptom/cause/fix)
    python3 tools/gen_dashboards.py                  # writes grafana/dashboards/*.json

Metric naming notes (Java SDK 1.40 + the lab's tally StatsReporter, lab.fleet.PromStatsReporter):
  * timers become histograms with a `_seconds` suffix, counters get `_total`,
    so names match the Go lab and the guide's tables;
  * worker identity is carried in the `process` and `worker_id` tags.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "grafana", "dashboards")
CATALOG = json.load(open(os.path.join(ROOT, "tools", "catalog.json")))
DS = {"type": "prometheus", "uid": "prometheus"}

RED, GREEN, BLUE, GREY, ORANGE, PURPLE = "#E02F44", "#56A64B", "#3274D9", "#8E8E8E", "#FF9830", "#A352CC"
W_LA = 'worker_type="LocalActivityWorker"'
W_WF = 'worker_type="WorkflowWorker"'


# ---------------------------------------------------------------- helpers
class Grid:
    """Places panels left to right, wrapping at 24 columns."""

    def __init__(self):
        self.x = self.y = self.row_h = 0
        self.panels = []
        self.next_id = 1

    def add(self, panel, w, h):
        if self.x + w > 24:
            self.y += self.row_h
            self.x = self.row_h = 0
        panel["id"] = self.next_id
        self.next_id += 1
        panel["gridPos"] = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.panels.append(panel)
        self.x += w
        self.row_h = max(self.row_h, h)
        return panel

    def newline(self):
        self.y += self.row_h
        self.x = self.row_h = 0

    def row(self, title):
        self.newline()
        self.add({"type": "row", "title": title, "collapsed": False, "panels": []}, 24, 1)
        self.newline()


def q(expr, legend="", ref=None, instant=False):
    t = {"expr": expr, "legendFormat": legend or "__auto", "range": not instant, "instant": instant, "datasource": DS}
    if ref:
        t["refId"] = ref
    return t


def _refs(targets):
    for i, t in enumerate(targets):
        t.setdefault("refId", chr(ord("A") + i))
    return targets


def limit_line(name, color=RED, dash=True):
    props = [
        {"id": "color", "value": {"mode": "fixed", "fixedColor": color}},
        {"id": "custom.fillOpacity", "value": 0},
        {"id": "custom.lineWidth", "value": 2},
        {"id": "custom.stacking", "value": {"mode": "none", "group": "A"}},
        {"id": "custom.drawStyle", "value": "line"},
    ]
    if dash:
        props.append({"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [10, 6]}})
    return {"matcher": {"id": "byName", "options": name}, "properties": props}


def color(name, c):
    return {"matcher": {"id": "byName", "options": name}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}


def regex_color(pattern, c):
    return {"matcher": {"id": "byRegexp", "options": pattern}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}


def ts(title, targets, unit="short", desc="", overrides=None, stack=False, bars=False, log=False, min0=True, decimals=None, max_=None):
    custom = {
        "drawStyle": "bars" if bars else "line",
        "lineWidth": 2,
        "fillOpacity": 70 if bars else (25 if stack else 8),
        "showPoints": "never",
        "spanNulls": True,
        "stacking": {"mode": "normal" if stack else "none", "group": "A"},
        "axisSoftMin": 0 if min0 else None,
        "lineInterpolation": "linear",
    }
    if log:
        custom["scaleDistribution"] = {"type": "log", "log": 10}
        custom.pop("axisSoftMin")
    defaults = {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"}}
    if decimals is not None:
        defaults["decimals"] = decimals
    if max_ is not None:
        defaults["max"] = max_
    return {
        "type": "timeseries",
        "title": title,
        "description": desc,
        "datasource": DS,
        "targets": _refs(targets),
        "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
        "options": {
            "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


def stat(title, targets, unit="short", desc="", decimals=1):
    return {
        "type": "stat",
        "title": title,
        "description": desc,
        "datasource": DS,
        "targets": _refs(targets),
        "fieldConfig": {
            "defaults": {"unit": unit, "decimals": decimals, "color": {"mode": "fixed", "fixedColor": "text"}},
            "overrides": [color("planned", GREY), color("measured", BLUE)],
        },
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "textMode": "value_and_name",
            "colorMode": "value",
            "graphMode": "none",
            "orientation": "horizontal",
            "justifyMode": "auto",
            "wideLayout": True,
        },
    }


def text(md, title=""):
    return {"type": "text", "title": title, "options": {"mode": "markdown", "content": md}}


def timeline(title, expr, legend="{{scenario}}"):
    return {
        "type": "state-timeline",
        "title": title,
        "datasource": DS,
        "targets": _refs([q(expr, legend)]),
        "fieldConfig": {
            "defaults": {
                "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": [{"color": GREY, "value": None}]},
                "mappings": [{"type": "value", "options": {
                    "0": {"text": "idle", "color": GREY, "index": 0},
                    "1": {"text": "baseline", "color": BLUE, "index": 1},
                    "2": {"text": "broken", "color": RED, "index": 2},
                    "3": {"text": "fixed", "color": GREEN, "index": 3},
                }}],
                "custom": {"fillOpacity": 80, "lineWidth": 0},
            },
            "overrides": [],
        },
        "options": {"showValue": "auto", "mergeValues": True, "rowHeight": 0.8, "alignValue": "left",
                    "legend": {"showLegend": False}, "tooltip": {"mode": "single"}},
    }


def annotations(tags_phase, tags_action):
    lst = [{
        "builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "hide": True,
        "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard",
    }, {
        "datasource": {"type": "datasource", "uid": "grafana"}, "enable": True, "iconColor": "#8AB8FF",
        "name": "Scenario phases", "target": {"type": "tags", "tags": tags_phase, "matchAny": False, "limit": 300},
    }, {
        "datasource": {"type": "datasource", "uid": "grafana"}, "enable": True, "iconColor": RED,
        "name": "Crashes and restarts", "target": {"type": "tags", "tags": tags_action, "matchAny": False, "limit": 100},
    }]
    return {"list": lst}


LINKS = [
    {"title": "Control page", "type": "link", "url": "http://localhost:8088", "targetBlank": True, "icon": "external link"},
    {"title": "Local Activity Lab", "type": "dashboards", "tags": ["local-activity-lab"], "asDropdown": True, "includeVars": False, "keepTime": True},
]


def dashboard(uid, title, grid, ann, desc="", time_from="now-15m"):
    return {
        "uid": uid,
        "title": title,
        "description": desc,
        "tags": ["local-activity-lab"],
        "timezone": "browser",
        "editable": True,
        "graphTooltip": 1,
        "schemaVersion": 39,
        "version": 1,
        "refresh": "5s",
        "time": {"from": time_from, "to": "now"},
        "timepicker": {"refresh_intervals": ["5s", "10s", "30s", "1m"]},
        "annotations": ann,
        "links": LINKS,
        "templating": {"list": []},
        "panels": grid.panels,
    }


def hq(quantile, bucket_expr, by="", window="30s"):
    by_clause = f"le{', ' + by if by else ''}"
    return f"histogram_quantile({quantile}, sum by ({by_clause}) (rate({bucket_expr}[{window}])))"


def per_wf(policy_re, kind):
    """Average count of a history event kind per sampled workflow."""
    return (f'sum(rate(demo_history_events_total{{policy=~"{policy_re}",kind="{kind}"}}[1m]))'
            f' / sum(rate(demo_history_sampled_total{{policy=~"{policy_re}"}}[1m]))')


# --------------------------------------------------- reusable panel builders
def e2e_panel(policy_re, title="Checkout end-to-end latency (caller's view)", by_policy=True):
    by = "policy" if by_policy else ""
    leg = " {{policy}}" if by_policy else ""
    return ts(title, [
        q(hq(0.5, f'demo_workflow_e2e_seconds_bucket{{policy=~"{policy_re}"}}', by), "p50" + leg),
        q(hq(0.99, f'demo_workflow_e2e_seconds_bucket{{policy=~"{policy_re}"}}', by), "p99" + leg),
    ], unit="s", desc="From the runner's StartWorkflow call to the workflow result.")


def results_panel(policy_re):
    return ts("Checkout outcomes per second", [
        q(f'sum by (status) (rate(demo_workflow_results_total{{policy=~"{policy_re}"}}[30s]))', "{{status}}"),
    ], unit="reqps", stack=True, bars=False,
        desc="completed · declined (invalid card, not retried) · deferred (fallback answered fast) · failed (budget exhausted) · error",
        overrides=[color("completed", GREEN), color("deferred", ORANGE), color("failed", RED), color("declined", PURPLE), color("error", "#8F3BB8")])


def wft_latency_panel(title="Workflow task execution latency (local activity time included)"):
    return ts(title, [
        q(hq(0.5, "temporal_workflow_task_execution_latency_seconds_bucket"), "p50"),
        q(hq(0.99, "temporal_workflow_task_execution_latency_seconds_bucket"), "p99"),
        q('max(demo_sizing_planned{quantity="heartbeat_point_seconds"})', "heartbeat point (80% of 10 s)"),
    ], unit="s", overrides=[limit_line("heartbeat point (80% of 10 s)")])


def query_latency_panel(policy_re):
    return ts("Query latency (status query 300 ms after start)", [
        q(hq(0.5, f'demo_query_latency_seconds_bucket{{policy=~"{policy_re}"}}', "policy"), "p50 {{policy}}"),
        q(hq(0.99, f'demo_query_latency_seconds_bucket{{policy=~"{policy_re}"}}', "policy"), "p99 {{policy}}"),
    ], unit="s", desc="Queries (like signals and updates) wait while a workflow task is open on a worker.")


def history_panel(policy_re, kinds, title="Per sampled workflow (from history)"):
    names = {"wft_completed": "workflow tasks", "wft_heartbeat": "workflow task heartbeats", "wft_timed_out": "workflow task timeouts",
             "timer": "timers", "marker": "markers", "activity_scheduled": "regular activities", "wft_failed": "workflow task failures"}
    targets = []
    for k in kinds:
        targets.append(q(f'sum by (policy) (rate(demo_history_events_total{{policy=~"{policy_re}",kind="{k}"}}[1m]))'
                         f' / sum by (policy) (rate(demo_history_sampled_total{{policy=~"{policy_re}"}}[1m]))',
                         f"{names[k]} · {{{{policy}}}}"))
    return ts(title, targets, unit="short", decimals=2,
              desc="The runner fetches the history of a sample of completed workflows and counts events. A heartbeat is a WorkflowTaskCompleted followed directly by WorkflowTaskScheduled.")


def la_slots_panel(show_available=True):
    t = [q(f"max by (worker_id) (temporal_worker_task_slots_used{{{W_LA}}})", "used {{worker_id}}")]
    ov = [limit_line("slot cap per worker")]
    t.append(q('max(demo_worker_config{setting="la_slots"})', "slot cap per worker"))
    return ts("Local activity slots used per worker", t, unit="short",
              desc="temporal_worker_task_slots_used{worker_type=\"LocalActivityWorker\"}. With the resource-based tuner the configured cap does not apply.",
              overrides=ov)


def la_latency_panel():
    return ts("Local activity latency: execution vs end-to-end", [
        q(hq(0.99, "temporal_local_activity_execution_latency_seconds_bucket"), "p99 execution (one attempt)"),
        q(hq(0.99, "temporal_local_activity_succeed_endtoend_latency_seconds_bucket"), "p99 end-to-end (schedule to success)"),
        q(hq(0.5, "temporal_local_activity_succeed_endtoend_latency_seconds_bucket"), "p50 end-to-end"),
        q('max(demo_sizing_planned{quantity="start_to_close_seconds"})', "StartToClose (sized)"),
        q('max(demo_sizing_planned{quantity="schedule_to_close_seconds"})', "ScheduleToClose (sized)"),
    ], unit="s", desc="The gap between end-to-end and execution is time spent waiting: for a slot, a rate-limiter token, or retry backoff.",
        overrides=[limit_line("StartToClose (sized)", ORANGE), limit_line("ScheduleToClose (sized)", RED)])


def api_codes_panel(log=False, title="Payments API requests per second by status"):
    return ts(title, [
        q('sum by (code) (rate(api_requests_total{service="payments"}[20s]))', "HTTP {{code}}"),
        q('max(api_limit{limit="rate_per_sec"})', "API rate limit"),
    ], unit="reqps", log=log, overrides=[limit_line("API rate limit"), color("HTTP 200", GREEN), color("HTTP 429", ORANGE),
                                          color("HTTP 503", RED), color("HTTP 400", PURPLE)])


def api_inflight_panel():
    return ts("Payments API concurrent requests vs limit", [
        q('max(api_inflight_peak{service="payments"})', "peak in flight (per scrape)"),
        q('max(api_limit{limit="concurrency"})', "API concurrency limit"),
        q('sum(sum by (process) (demo_worker_config{setting="la_slots"}) * on (process) sum by (process) (demo_fleet_workers)) '
          'and on() max(demo_worker_config{setting="resource_tuner"}) == 0', "fleet slot cap (workers × slots)"),
    ], unit="short", overrides=[limit_line("API concurrency limit"), limit_line("fleet slot cap (workers × slots)", ORANGE)])


def rejections_panel():
    return ts("API rejections (429) by reason", [
        q("sum by (reason) (rate(api_rejections_total[20s]))", "{{reason}}"),
    ], unit="reqps", stack=True, overrides=[color("rate", ORANGE), color("concurrency", RED), color("quota", PURPLE)])


def fleet_panel():
    return ts("Workers running", [
        q("sum by (process) (demo_fleet_workers)", "{{process}}"),
        q("sum(demo_fleet_workers)", "total"),
        q('max(demo_sizing_planned{quantity="min_workers"})', "sized minimum"),
        q('max(demo_sizing_planned{quantity="max_workers"})', "autoscaler max"),
    ], unit="short", decimals=0, overrides=[limit_line("sized minimum", ORANGE), limit_line("autoscaler max", GREY)])


def breaker_panel():
    return ts("Circuit breaker state (0 closed · 0.5 probing · 1 open)", [
        q("max by (process, step) (demo_circuit_breaker_state)", "{{process}} {{step}}"),
    ], unit="short", max_=1)


def side_effects_panel():
    return ts("Re-executed calls at the API (per minute)", [
        q("sum by (step) (increase(api_duplicate_side_effects_total[1m]))", "DUPLICATE side effect · {{step}}"),
        q("sum by (step) (increase(api_idempotent_replays_total[1m]))", "idempotent replay · {{step}}"),
    ], unit="short", bars=True, stack=True, desc="A duplicate is a second charge/email for the same order and step. A replay is the same re-execution answered from the idempotency store.",
        overrides=[regex_color("DUPLICATE.*", RED), regex_color("idempotent.*", GREEN)])


def wft_timeouts_panel(policy_re):
    return ts("Workflow task timeouts in sampled histories (per minute)", [
        q(f'sum by (policy) (increase(demo_history_events_total{{policy=~"{policy_re}",kind="wft_timed_out"}}[1m]))', "{{policy}}"),
    ], unit="short", bars=True, desc="Sampled workflows only (see the scenario's history sample rate).")


def scenario_text(sc):
    phases = "\n".join(
        f"| {i + 1} | {p['variant']} | {p['name']} | {int(p['duration_sec'])} s | {p.get('note', '') or ''} |"
        for i, p in enumerate(sc["phases"]))
    return (f"### {sc['title']}\n"
            f"**Guide:** {sc['guide']}  ·  **Symptom:** {sc['symptom']}  ·  **Cause:** {sc['cause']}  ·  **Fix:** {sc['fix']}\n\n"
            f"**What to watch:** {sc['watch']}\n\n"
            f"| # | Variant | Phase | Length | What changes |\n|---|---|---|---|---|\n{phases}\n\n"
            f"Run it from the [control page](http://localhost:8088). Blue regions mark phases; red markers are crashes and restarts.")


# --------------------------------------------------------- dashboard: sizing
def sizing_dashboard():
    g = Grid()
    g.add(text(
        "### Worked sizing example, live\n"
        "The guide sizes a fleet for **300 checkouts/s** against a payments API capped at **1,000 req/s** and **200 concurrent connections**, "
        "with at most **10 workers**. This lab runs the same derivation at `SCALE` (default 0.2: every rate and limit × 0.2, every ratio unchanged).\n\n"
        "**Concurrency limit → slots per worker. Rate limit → fleet floor. Latency → timeouts.** "
        "Grey numbers are the plan, blue numbers are measured. Run *Worked sizing example* on the [control page](http://localhost:8088) "
        "to step the fleet 8 → 10 → 6 → 8 workers; at 6 the fleet's rate cap drops below demand and local activities start waiting for tokens.\n\n"
        "_Java note:_ the Java SDK has no per-worker local activity rate option, so the lab enforces the per-worker cap in the activity "
        "(a token bucket with burst 1, like Go's). The wait **holds a local activity slot and counts against StartToClose**, so it shows up "
        "inside execution latency; the *per-worker token wait* panel isolates it."), 24, 6)
    g.add(timeline("Active scenario", "demo_scenario_variant"), 24, 3)

    P = lambda k: q(f'max(demo_sizing_planned{{quantity="{k}"}})', "planned")
    M = lambda e: q(e, "measured")
    fleet = "sum(demo_fleet_workers)"
    g.row("Plan vs measured (guide's derivation table)")
    stats = [
        ("Workload (workflows/s)", "short", [P("workflows_per_sec"), M("sum(rate(demo_workflows_started_total[30s]))")]),
        ("API calls/s (incl. ~5% retries)", "reqps", [P("api_calls_per_sec"), M('sum(rate(api_requests_total{service="payments"}[30s]))')]),
        ("API rate limit vs accepted calls", "reqps", [P("api_rate_limit"), M('sum(rate(api_requests_total{service="payments",code!="429"}[30s]))')]),
        ("API concurrency limit vs peak", "short", [P("api_concurrency"), M('max(max_over_time(api_inflight_peak{service="payments"}[1m]))')]),
        ("Workers (minimum)", "short", [P("min_workers"), M(fleet)]),
        ("LA slots per worker (cap vs peak used)", "short", [P("la_slots_per_worker"),
                                                            M(f"max(max_over_time(temporal_worker_task_slots_used{{{W_LA}}}[1m]))")]),
        ("Per-worker rate cap vs busiest worker", "reqps", [P("la_rate_per_worker"),
                                                          M("max(sum by (worker_id) (rate(temporal_local_activity_total[30s])))")]),
        ("Calls per worker", "reqps", [P("calls_per_worker_at_min"), M(f"sum(rate(temporal_local_activity_total[30s])) / {fleet}")]),
        ("Concurrent calls per worker (avg, Little's law)", "short", [P("peak_concurrent_per_worker"),
                                                                       M(f"sum(rate(temporal_local_activity_execution_latency_seconds_sum[30s])) / {fleet}")]),
        ("Workflow tasks/s per worker", "short", [P("wft_per_sec_per_worker"),
                                                  M(f"sum(rate(temporal_workflow_task_execution_latency_seconds_count[30s])) / {fleet}")]),
        ("Concurrent workflow tasks per worker", "short", [P("concurrent_wft_per_worker"),
                                                           M(f"sum(rate(temporal_workflow_task_execution_latency_seconds_sum[30s])) / {fleet}")]),
        ("Workflow task p99 vs worst case", "s", [P("worst_case_wft_seconds"), M(hq(0.99, "temporal_workflow_task_execution_latency_seconds_bucket", window="1m"))]),
    ]
    descs = {
        "Workflow tasks/s per worker": "The guide assumes ~2 workflow tasks per workflow. When its local activities finish quickly a workflow usually completes in one, so the measured value is about half.",
        "Concurrent calls per worker (avg, Little's law)": "Planned value is the peak (calls per worker × p99); measured is the average (sum of execution time per second).",
    }
    for title, unit, targets in stats:
        g.add(stat(title, targets, unit=unit, desc=descs.get(title, "")), 4, 4)

    g.row("Limits")
    g.add(ts("Payments API requests/s vs rate limit and fleet rate cap", [
        q('sum by (code) (rate(api_requests_total{service="payments"}[20s]))', "HTTP {{code}}"),
        q('max(api_limit{limit="rate_per_sec"})', "API rate limit"),
        q('sum(sum by (process) (demo_worker_config{setting="la_rate"}) * on (process) sum by (process) (demo_fleet_workers))', "fleet rate cap (workers × per-worker rate)"),
        q('max(demo_sizing_planned{quantity="api_calls_per_sec"})', "planned demand"),
    ], unit="reqps", stack=False, overrides=[limit_line("API rate limit"), limit_line("fleet rate cap (workers × per-worker rate)", ORANGE),
                                              limit_line("planned demand", GREY), color("HTTP 200", GREEN), color("HTTP 503", RED), color("HTTP 429", ORANGE)]), 12, 9)
    g.add(api_inflight_panel(), 12, 9)
    g.add(la_slots_panel(), 12, 9)
    g.add(ts("Local activity attempts/s per worker vs per-worker cap", [
        q("sum by (worker_id) (rate(temporal_local_activity_total[20s]))", "{{worker_id}}"),
        q('max(demo_worker_config{setting="la_rate"})', "per-worker cap (in-code token bucket)"),
    ], unit="reqps", overrides=[limit_line("per-worker cap (in-code token bucket)")]), 12, 9)
    g.add(ts("Per-worker token wait inside the local activity (Java: holds the slot)", [
        q(hq(0.5, "demo_la_rate_wait_seconds_bucket"), "p50"),
        q(hq(0.99, "demo_la_rate_wait_seconds_bucket"), "p99"),
        q('max(demo_sizing_planned{quantity="start_to_close_seconds"}) / 2', "give-up point (half the attempt budget)"),
    ], unit="s", overrides=[limit_line("give-up point (half the attempt budget)")],
        desc="Time a payments call waited for the per-worker rate token. Rises sharply when the fleet's rate cap falls below demand."), 24, 8)

    g.row("Latency budget")
    g.add(la_latency_panel(), 12, 9)
    g.add(wft_latency_panel(), 12, 9)
    g.add(fleet_panel(), 8, 8)
    g.add(e2e_panel(".*", by_policy=False), 8, 8)
    g.add(results_panel(".*"), 8, 8)
    return dashboard("la-sizing", "Local Activity Lab · Worked sizing example", g, annotations(["phase", "sizing"], ["action", "sizing"]),
                     desc="The guide's worked sizing example, planned vs measured.")


# --------------------------------------------------- dashboards: failure modes
def failure_panels(sc_id):
    """Panels for each scenario, chosen to show its symptom and its fix."""
    P = {
        "api-slowdown": lambda g: [
            g.add(wft_latency_panel(), 12, 8),
            g.add(query_latency_panel("slowdown_.*"), 12, 8),
            g.add(e2e_panel("slowdown_.*"), 12, 8),
            g.add(results_panel("slowdown_.*"), 12, 8),
            g.add(history_panel("slowdown_.*", ["wft_heartbeat"]), 8, 8),
            g.add(api_codes_panel(), 8, 8),
            g.add(breaker_panel(), 8, 8),
        ],
        "slot-exhaustion": lambda g: [
            g.add(ts("Local activity slots available (lowest worker)", [
                q(f"min(temporal_worker_task_slots_available{{{W_LA}}})", "min available"),
                q(f"max(temporal_worker_task_slots_used{{{W_LA}}})", "max used"),
                q('max(demo_worker_config{setting="la_slots"})', "slot cap per worker"),
            ], unit="short", desc="Sustained 0 available = slot exhaustion (fixed-size suppliers only).",
                overrides=[limit_line("slot cap per worker"), color("min available", RED)]), 12, 8),
            g.add(la_latency_panel(), 12, 8),
            g.add(results_panel("slots_.*"), 8, 8),
            g.add(e2e_panel("slots_.*"), 8, 8),
            g.add(wft_latency_panel(), 8, 8),
        ],
        "wft-heartbeat": lambda g: [
            g.add(query_latency_panel("heartbeat_.*"), 12, 8),
            g.add(history_panel("heartbeat_.*", ["wft_heartbeat", "wft_completed", "activity_scheduled"]), 12, 8),
            g.add(e2e_panel("heartbeat_.*"), 8, 8),
            g.add(wft_latency_panel(), 8, 8),
            g.add(la_slots_panel(), 8, 8),
        ],
        "pod-loss": lambda g: [
            g.add(ts("Checkout latency (podloss)", [
                q(hq(0.5, 'demo_workflow_e2e_seconds_bucket{policy="podloss"}'), "p50"),
                q(hq(0.99, 'demo_workflow_e2e_seconds_bucket{policy="podloss"}'), "p99"),
                q(hq(0.999, 'demo_workflow_e2e_seconds_bucket{policy="podloss"}'), "p99.9"),
            ], unit="s", desc="Workflows whose task was in flight on the killed worker wait out the 10 s workflow task timeout (sticky queue → normal queue)."), 12, 8),
            g.add(wft_timeouts_panel("podloss"), 12, 8),
            g.add(fleet_panel(), 8, 8),
            g.add(ts("Workflows in flight", [q("demo_inflight_workflows", "in flight")], unit="short"), 8, 8),
            g.add(results_panel("podloss"), 8, 8),
        ],
        "duplicates": lambda g: [
            g.add(side_effects_panel(), 24, 9),
            g.add(ts("Duplicate side effects (cumulative since start)", [
                q("sum by (step) (api_duplicate_side_effects_total)", "{{step}}"),
            ], unit="short", stack=True), 8, 8),
            g.add(wft_timeouts_panel("dupes_.*"), 8, 8),
            g.add(fleet_panel(), 8, 8),
        ],
        "fleet-scale-out": lambda g: [
            g.add(ts("Payments API requests/s vs limit and fleet rate cap", [
                q('sum by (code) (rate(api_requests_total{service="payments"}[20s]))', "HTTP {{code}}"),
                q('max(api_limit{limit="rate_per_sec"})', "API rate limit"),
                q('sum(sum by (process) (demo_worker_config{setting="la_rate"}) * on (process) sum by (process) (demo_fleet_workers)) '
                  'and on() min(demo_worker_config{setting="la_rate"}) > 0', "fleet rate cap (workers × per-worker rate)"),
            ], unit="reqps", stack=True, overrides=[limit_line("API rate limit"), limit_line("fleet rate cap (workers × per-worker rate)", ORANGE),
                                                    color("HTTP 200", GREEN), color("HTTP 429", ORANGE), color("HTTP 503", RED)]), 12, 9),
            g.add(rejections_panel(), 12, 9),
            g.add(fleet_panel(), 8, 8),
            g.add(la_latency_panel(), 8, 8),
            g.add(ts("Shared limiter", [
                q("sum(rate(limiter_acquired_total[20s]))", "tokens granted/s"),
                q("sum(rate(limiter_rejected_total[20s]))", "no token in time/s"),
                q("max(limiter_rate_per_sec)", "limiter rate"),
            ], unit="reqps", overrides=[limit_line("limiter rate")]), 8, 8),
            g.add(results_panel("scaleout"), 24, 7),
        ],
        "retry-storm": lambda g: [
            g.add(api_codes_panel(log=True, title="Payments API requests per second by status (log scale)"), 12, 9),
            g.add(ts("Amplification: API calls per started workflow", [
                q('sum(rate(api_requests_total{service="payments"}[20s])) / sum(rate(demo_workflows_started_total[20s]))', "API calls per workflow"),
            ], unit="short", decimals=1, desc="Two calls per workflow is normal. During the broken outage every workflow keeps retrying every 250 ms."), 12, 9),
            g.add(breaker_panel(), 8, 8),
            g.add(results_panel("storm_.*"), 8, 8),
            g.add(history_panel("storm_.*", ["wft_heartbeat"]), 8, 8),
        ],
        "long-backoff": lambda g: [
            g.add(history_panel("backoff_.*", ["timer", "wft_completed", "activity_scheduled"]), 12, 9),
            g.add(e2e_panel("backoff_.*"), 12, 9),
            g.add(rejections_panel(), 8, 8),
            g.add(results_panel("backoff_.*"), 8, 8),
            g.add(text("**Java's local retry threshold defaults to 3 × the workflow task timeout (30 s here)** and is set per call with "
                       "`LocalActivityOptions.setLocalRetryThreshold`. A backoff (or `Retry-After`) up to that is retried in process, holding the "
                       "workflow task open (heartbeating every 8 s); a longer one is recorded in a marker and a durable timer is started, which brings "
                       "back a workflow task per retry. The lab's API answers with `Retry-After: 40 s` to cross it."), 8, 8),
        ],
        "long-chain": lambda g: [
            g.add(ts("Calls re-executed after the crash, by step (per minute)", [
                q("sum by (step) (increase(api_reexecutions_total[1m]))", "{{step}}"),
            ], unit="short", bars=True, stack=True, desc="Every call for an order+step that had already succeeded (answered from the idempotency store here, so no duplicates)."), 12, 9),
            g.add(e2e_panel("chain_.*"), 12, 9),
            g.add(wft_timeouts_panel("chain_.*"), 8, 8),
            g.add(history_panel("chain_.*", ["timer", "wft_completed"]), 8, 8),
            g.add(fleet_panel(), 8, 8),
        ],
        "resource-tuner": lambda g: [
            g.add(ts("Local activity slots in use (fleet) vs API concurrency limit", [
                q(f"sum(temporal_worker_task_slots_used{{{W_LA}}})", "slots used (all workers)"),
                q('max(api_limit{limit="concurrency"})', "API concurrency limit"),
            ], unit="short", overrides=[limit_line("API concurrency limit")]), 12, 9),
            g.add(api_inflight_panel(), 12, 9),
            g.add(rejections_panel(), 8, 8),
            g.add(ts("CPU and JVM heap seen by the tuner (JVMSystemResourceInfo)", [
                q("max by (resource) (demo_sysinfo_usage)", "{{resource}}"),
            ], unit="percentunit", max_=1, desc="ResourceBasedController targets 90% CPU and 90% heap. I/O-bound calls barely move either, so it keeps issuing slots."), 8, 8),
            g.add(results_panel("tuner_.*"), 8, 8),
        ],
    }
    return P[sc_id]


def failure_dashboards():
    out = []
    index_rows = []
    for sc in CATALOG:
        if sc["id"] == "sizing":
            continue
        uid = "la-fm-" + sc["id"]
        g = Grid()
        g.add(text(scenario_text(sc)), 24, 7)
        g.add(timeline("Phase", f'demo_scenario_variant{{scenario="{sc["id"]}"}}'), 24, 3)
        failure_panels(sc["id"])(g)
        out.append((uid, dashboard(uid, f"Local Activity Lab · {sc['guide'].replace('Failure modes: ', '')} · {sc['title']}", g,
                                   annotations(["phase", sc["id"]], ["action", sc["id"]]), desc=sc["symptom"])))
        index_rows.append(f"| [{sc['title']}](/d/{uid}) | {sc['symptom']} | {sc['cause']} | {sc['fix']} |")

    g = Grid()
    g.add(text("### Failure modes and anti-patterns\nOne dashboard per row of the guide's table. Start a scenario from the "
               "[control page](http://localhost:8088); each one runs the broken configuration, recovers, then runs the fix.\n\n"
               "| Scenario | Symptom | Cause | Fix |\n|---|---|---|---|\n" + "\n".join(index_rows) +
               "\n\n_Not simulated:_ non-determinism from changing local activity code or options without versioning (a deployment issue)."), 24, 14)
    g.add(timeline("Scenarios over time", "demo_scenario_variant"), 24, 6)
    g.add(e2e_panel(".*", title="Checkout latency, all traffic", by_policy=False), 12, 8)
    g.add(results_panel(".*"), 12, 8)
    out.append(("la-failures", dashboard("la-failures", "Local Activity Lab · Failure modes", g,
                                         annotations(["phase"], ["action"]), time_from="now-1h")))
    return out


# -------------------------------------------------- dashboard: observability
def observability_dashboard():
    g = Grid()
    g.add(text(
        "### The guide's observability table, with its alert thresholds drawn as red dashed lines\n"
        "Metric names as exported by the **Java SDK (tally scope) through the lab's Prometheus StatsReporter**: latency timers become `_seconds` histograms, "
        "counters end in `_total` (the same names the Go SDK's Prometheus reporter and Micrometer's Prometheus registry produce). "
        "Core-based SDKs (TypeScript, Python, .NET) report milliseconds by default.\n\n"
        "```promql\n# p99 local activity attempt latency by activity type\n"
        "histogram_quantile(0.99, sum by (le, activity_type) (rate(temporal_local_activity_execution_latency_seconds_bucket[5m])))\n\n"
        "# local activity slot utilisation per worker\n"
        "max by (worker_id) (temporal_worker_task_slots_used{worker_type=\"LocalActivityWorker\"})\n"
        "  / on() group_left max(demo_worker_config{setting=\"la_slots\"})\n```"), 24, 9)
    g.add(timeline("Active scenario", "demo_scenario_variant"), 24, 3)

    g.row("Local activities")
    g.add(ts("1 · temporal_local_activity_execution_latency p99 (alert > 50% of StartToClose)", [
        q(hq(0.99, "temporal_local_activity_execution_latency_seconds_bucket", "activity_type"), "p99 {{activity_type}}"),
        q('max(demo_sizing_planned{quantity="start_to_close_seconds"}) * 0.5', "alert: 50% of StartToClose"),
    ], unit="s", overrides=[limit_line("alert: 50% of StartToClose")]), 12, 8)
    g.add(ts("2 · temporal_local_activity_succeed_endtoend_latency p99 (alert > 50% of ScheduleToClose)", [
        q(hq(0.99, "temporal_local_activity_succeed_endtoend_latency_seconds_bucket"), "p99"),
        q('max(demo_sizing_planned{quantity="schedule_to_close_seconds"}) * 0.5', "alert: 50% of ScheduleToClose"),
    ], unit="s", overrides=[limit_line("alert: 50% of ScheduleToClose")]), 12, 8)
    g.add(ts("3 · Failure ratio: temporal_local_activity_execution_failed / temporal_local_activity_total (alert > 1%)", [
        q("sum(rate(temporal_local_activity_execution_failed_total[1m])) / sum(rate(temporal_local_activity_total[1m]))", "failure ratio"),
        q("vector(0.01)", "alert: 1%"),
    ], unit="percentunit", overrides=[limit_line("alert: 1%")],
        desc="Counts failed attempts (including ones that later succeed on retry)."), 12, 8)
    g.add(ts("4 · temporal_local_activity_total rate vs API rate limit (alert at 80%)", [
        q("sum(rate(temporal_local_activity_total[30s]))", "local activity attempts/s"),
        q('max(api_limit{limit="rate_per_sec"}) * 0.8', "alert: 80% of API rate limit"),
        q('max(api_limit{limit="rate_per_sec"})', "API rate limit"),
    ], unit="reqps", overrides=[limit_line("alert: 80% of API rate limit", ORANGE), limit_line("API rate limit")]), 12, 8)
    g.add(ts("5 · temporal_worker_task_slots_used (LocalActivityWorker) per worker (alert > 80% of slots)", [
        q(f"max by (worker_id) (temporal_worker_task_slots_used{{{W_LA}}})", "{{worker_id}}"),
        q('max(demo_worker_config{setting="la_slots"}) * 0.8', "alert: 80% of slots"),
    ], unit="short", overrides=[limit_line("alert: 80% of slots")]), 12, 8)
    g.add(ts("6 · temporal_worker_task_slots_available (LocalActivityWorker), lowest worker (alert: sustained 0)", [
        q(f"min(temporal_worker_task_slots_available{{{W_LA}}})", "min available"),
        q("vector(0)", "alert: 0"),
    ], unit="short", desc="Only meaningful with fixed-size slot suppliers; use slots_used with resource-based ones.",
        overrides=[limit_line("alert: 0")]), 12, 8)

    g.row("Workflow tasks")
    g.add(ts("7 · temporal_workflow_task_execution_latency p99 (alert approaching 50% of the 10 s task timeout)", [
        q(hq(0.99, "temporal_workflow_task_execution_latency_seconds_bucket"), "p99"),
        q("vector(5)", "alert: 5 s (50% of task timeout)"),
        q('max(demo_sizing_planned{quantity="heartbeat_point_seconds"})', "heartbeat point (8 s)"),
    ], unit="s", overrides=[limit_line("alert: 5 s (50% of task timeout)", ORANGE), limit_line("heartbeat point (8 s)")]), 8, 8)
    g.add(ts("8 · temporal_workflow_task_schedule_to_start_latency p99 (alert > 100 ms)", [
        q(hq(0.99, "temporal_workflow_task_schedule_to_start_latency_seconds_bucket"), "p99"),
        q("vector(0.1)", "alert: 100 ms"),
    ], unit="s", overrides=[limit_line("alert: 100 ms")]), 8, 8)
    g.add(ts("9 · temporal_sticky_cache_miss vs hit", [
        q("sum(rate(temporal_sticky_cache_hit_total[1m]))", "hits/s"),
        q("sum(rate(temporal_sticky_cache_miss_total[1m]))", "misses/s"),
    ], unit="reqps", desc="Misses rise after a deploy, crash or scale-out: the workflow is replayed from history.",
        overrides=[color("misses/s", RED), color("hits/s", GREEN)]), 8, 8)

    g.row("From the API side, and what Temporal can't see")
    g.add(api_codes_panel(), 8, 8)
    g.add(ts("Pool waits hide inside StartToClose", [
        q(hq(0.99, 'demo_api_call_latency_seconds_bucket{step=~"authorize|capture"}'), "p99 client view (activity, incl. pool wait)"),
        q(hq(0.99, 'api_request_duration_seconds_bucket{service="payments"}'), "p99 server view"),
    ], unit="s", desc="The difference is time spent waiting for a pooled connection or on the network. It counts against StartToClose but no Temporal metric shows it."), 8, 8)
    g.add(history_panel(".*", ["marker", "wft_heartbeat", "wft_completed"], title="History: markers, workflow tasks and heartbeats per workflow"), 8, 8)
    return dashboard("la-observability", "Local Activity Lab · Observability", g, annotations(["phase"], ["action"]),
                     desc="The guide's metrics table, alerts drawn as thresholds.")


def main():
    os.makedirs(OUT, exist_ok=True)
    dashes = [("la-sizing", sizing_dashboard()), ("la-observability", observability_dashboard())] + failure_dashboards()
    for uid, d in dashes:
        with open(os.path.join(OUT, uid + ".json"), "w") as f:
            json.dump(d, f, indent=1)
    print(f"wrote {len(dashes)} dashboards to {OUT}")


if __name__ == "__main__":
    main()
