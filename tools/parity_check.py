"""M7 parity check: does the Java app reproduce the Python evaluation harness exactly?

For each (seed, scenario) it
  1. creates a SIMULATED_DEMO semester in the running backend and plays all 336 meals through the app
     (backend -> ML service -> simulator service), in chunks of one week;
  2. runs the harness's LINUCB strategy on the same seed and scenario, in-process;
  3. compares the two meal by meal: deployed layout (policy labels), reward, carbon, waste, uptake,
     number of safety events and whether a rollback fired.

Needs the backend (8080), the ML service (8001) and the simulator service (8002) running.

    .venv\\Scripts\\python tools\\parity_check.py
    .venv\\Scripts\\python tools\\parity_check.py --seeds 100 101 --scenarios FAILED_SUBSTITUTION

Small numeric differences are expected because the database stores some values with fixed decimals
(carbon score 6, waste 2); the tolerances below allow for that. A different layout choice is a real
mismatch, and every meal after it is reported but not counted against the app, because the two runs
no longer see the same history.
Exit code 0 means every compared run matched.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

from eval_harness.runner import run_semester  # noqa: E402

START_DATE = "2026-07-06"      # the simulator's calendar
CHUNK = 21                     # meals per request (one week)


# ------------------------------------------------------------------ backend calls
def call(api: str, method: str, path: str, body: dict | None = None, timeout: int = 900):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(api.rstrip("/") + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} failed: HTTP {e.code}\n{e.read().decode(errors='replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"cannot reach the backend at {api} ({e.reason}). Start the backend, ML service and simulator.")


def run_app(api: str, seed: int, scenario: str, enrolled: int) -> list[dict]:
    sem = call(api, "POST", "/api/semesters", {
        "name": f"parity-{scenario}-{seed}-{int(time.time())}", "mode": "SIMULATED_DEMO",
        "startDate": START_DATE, "enrolledStudents": enrolled, "simSeed": seed, "simScenario": scenario})
    total = sem["mealCount"]
    rows: list[dict] = []
    t0 = time.time()
    while len(rows) < total:
        n = min(CHUNK, total - len(rows))
        rows.extend(call(api, "POST", f"/api/semesters/{sem['id']}/demo/run?meals={n}"))
        print(f"\r    app: {len(rows):3d}/{total} meals  ({time.time() - t0:5.0f}s)", end="", flush=True)
    print()
    return sorted(rows, key=lambda r: r["mealIndex"])


# ------------------------------------------------------------------ comparison
def close(a, b, abs_tol: float, rel_tol: float = 0.0) -> bool:
    if a is None or b is None:
        return a is None and b is None
    return math.isclose(a, b, abs_tol=abs_tol, rel_tol=rel_tol)


def compare(app: list[dict], harness: list[dict], tol: dict) -> dict:
    first_divergence = None
    numeric_mismatch = []
    max_diff = {"reward": 0.0, "carbon": 0.0, "waste_g": 0.0, "uptake": 0.0}
    for a, h in zip(app, harness):
        t = h["t"]
        same_layout = sorted(a["deployedLabels"]) == sorted(h["labels"].split("+"))
        if not same_layout and first_divergence is None:
            first_divergence = t
        if first_divergence is not None:
            continue                                   # histories differ from here on
        pairs = {"reward": (a["reward"], h["reward"]), "carbon": (a["carbonPerAttendee"], h["carbon"]),
                 "waste_g": (a["wasteGPerAttendee"], h["waste_g"]), "uptake": (a["eligibleUptake"], h["uptake"])}
        problems = []
        for key, (x, y) in pairs.items():
            if x is not None and y is not None:
                max_diff[key] = max(max_diff[key], abs(x - y))
            if not close(x, y, tol[key]):
                problems.append(f"{key} app={x} harness={y}")
        app_events = len(a["safetyEvents"])
        app_rollback = any(e["action"] == "ROLLBACK" for e in a["safetyEvents"])
        if app_events != h["n_events"]:
            problems.append(f"safety events app={app_events} harness={h['n_events']}")
        if app_rollback != h["rollback"]:
            problems.append(f"rollback app={app_rollback} harness={h['rollback']}")
        if problems:
            numeric_mismatch.append((t, problems))
    compared = first_divergence if first_divergence is not None else len(harness)
    return {"compared": compared, "first_divergence": first_divergence,
            "numeric_mismatch": numeric_mismatch, "max_diff": max_diff}


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else float("nan")


def show_context(app, harness, t, width=3):
    print("      t   phase     app labels      harness labels   app reward   harness reward")
    for i in range(max(0, t - width), min(len(harness), t + width + 1)):
        a, h = app[i], harness[i]
        mark = ">>" if i == t else "  "
        ar = "None" if a["reward"] is None else f"{a['reward']:+.6f}"
        hr = "None" if h["reward"] is None else f"{h['reward']:+.6f}"
        print(f"   {mark}{i:4d}  {h['phase']:<8}  {'+'.join(a['deployedLabels']):<14}  {h['labels']:<15}  "
              f"{ar:>11}  {hr:>14}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seeds", type=int, nargs="+", default=[100])
    ap.add_argument("--scenarios", nargs="+", default=["SUCCESSFUL_NUDGING", "FAILED_SUBSTITUTION"])
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--reward-tol", type=float, default=1e-4)
    ap.add_argument("--carbon-tol", type=float, default=1e-4)
    ap.add_argument("--waste-tol", type=float, default=1e-2, help="grams per attendee")
    ap.add_argument("--uptake-tol", type=float, default=1e-4)
    args = ap.parse_args()
    tol = {"reward": args.reward_tol, "carbon": args.carbon_tol, "waste_g": args.waste_tol, "uptake": args.uptake_tol}

    config = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
    enrolled = config["semester"]["enrolled_students"]
    call(args.api, "GET", "/api/config/v1")       # fail fast if the backend is down

    all_ok = True
    summary = []
    for scenario in args.scenarios:
        for seed in args.seeds:
            print(f"\n=== {scenario}  seed {seed}")
            t0 = time.time()
            harness = run_semester(seed, scenario, "LINUCB", config, compute_regret=False)
            print(f"    harness: {len(harness)} meals ({time.time() - t0:.0f}s)")
            app = run_app(args.api, seed, scenario, enrolled)
            if len(app) != len(harness):
                print(f"    MISMATCH: app returned {len(app)} meals, harness {len(harness)}")
                all_ok = False
                continue
            r = compare(app, harness, tol)
            post = [i for i, h in enumerate(harness) if h["phase"] != "BASELINE"]
            line = {
                "scenario": scenario, "seed": seed, "same_layout": r["compared"], "meals": len(harness),
                "numeric_mismatches": len(r["numeric_mismatch"]),
                "reward_app": mean(app[i]["reward"] for i in post),
                "reward_harness": mean(harness[i]["reward"] for i in post),
                "carbon_app": mean(app[i]["carbonPerAttendee"] for i in post),
                "carbon_harness": mean(harness[i]["carbon"] for i in post),
                "rollbacks_app": sum(any(e["action"] == "ROLLBACK" for e in app[i]["safetyEvents"]) for i in post),
                "rollbacks_harness": sum(harness[i]["rollback"] for i in post),
            }
            summary.append(line)
            ok = r["first_divergence"] is None and not r["numeric_mismatch"]
            all_ok &= ok
            print(f"    identical layout choices: {r['compared']}/{len(harness)}")
            print("    largest differences before any divergence: "
                  + ", ".join(f"{k} {v:.2e}" for k, v in r["max_diff"].items()))
            print(f"    mean reward  app {line['reward_app']:+.6f}  harness {line['reward_harness']:+.6f}")
            print(f"    mean carbon  app {line['carbon_app']:.6f}  harness {line['carbon_harness']:.6f}  kg/attendee")
            print(f"    rollbacks    app {line['rollbacks_app']}  harness {line['rollbacks_harness']}")
            if r["first_divergence"] is not None:
                print(f"    FIRST DIFFERENT LAYOUT at meal {r['first_divergence']}:")
                show_context(app, harness, r["first_divergence"])
            for t, problems in r["numeric_mismatch"][:10]:
                print(f"    meal {t}: " + "; ".join(problems))
            if len(r["numeric_mismatch"]) > 10:
                print(f"    ... and {len(r['numeric_mismatch']) - 10} more meals with numeric differences")
            print("    RESULT: " + ("MATCH" if ok else "DIFFERENT"))

    out = ROOT / "results" / "parity_summary.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\nsummary written to {out}")
    print("ALL RUNS MATCH" if all_ok else "SOME RUNS DIFFER - paste this output to Claude")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
