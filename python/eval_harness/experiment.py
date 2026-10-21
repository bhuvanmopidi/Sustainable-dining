"""Batch evaluation: every strategy on matched simulated semesters, paired comparisons.

Examples (run from the repo root):
  python python/eval_harness/experiment.py --scenarios NO_PLACEMENT_EFFECT SUCCESSFUL_NUDGING --seeds 100-109
  python python/eval_harness/experiment.py --seeds 100-199 --lambdas 0.5 1.0 2.0

Outputs in results/: runs.csv (one row per seed x scenario x lambda x strategy)
                     paired.csv (LINUCB minus each baseline, with 95% CI over seeds)
All numbers are SIMULATED results, not real-world findings.
"""
from __future__ import annotations

import argparse
import copy
import csv
import json
import math
import os
import statistics
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from eval_harness.runner import STRATEGIES, run_semester, summarise  # noqa: E402
from simulator.scenarios import SCENARIOS  # noqa: E402

METRICS = ("carbon", "waste_g", "uptake", "reward", "dissatisfaction", "regret_total", "rollbacks")
FIXED = ("FIXED_P0", "FIXED_P1", "FIXED_P2", "FIXED_P3")


def parse_seeds(text: str) -> list[int]:
    lo, _, hi = text.partition("-")
    return list(range(int(lo), int(hi or lo) + 1))


def run_one(job):
    seed, scenario, strategy, lam, config, regret, overrides = job
    cfg = copy.deepcopy(config)
    cfg["reward"]["lambda_w"] = lam
    for (section, key), value in overrides.items():
        cfg[section][key] = value
    summary = summarise(run_semester(seed, scenario, strategy, cfg, compute_regret=regret))
    return {"seed": seed, "scenario": scenario, "lambda_w": lam, "strategy": strategy, **summary}


def paired_ci(diffs: list[float]) -> tuple[float, float, float]:
    """Mean difference and normal-approximation 95% CI over seeds."""
    n = len(diffs)
    mean = sum(diffs) / n
    half = 1.96 * statistics.stdev(diffs) / math.sqrt(n) if n > 1 else float("nan")
    return mean, mean - half, mean + half


def add_best_fixed(rows: list[dict]) -> list[dict]:
    """BEST_FIXED: per seed, the fixed policy with the highest mean reward in hindsight."""
    groups = {}
    for r in rows:
        if r["strategy"] in FIXED and r["reward"] is not None:
            groups.setdefault((r["seed"], r["scenario"], r["lambda_w"]), []).append(r)
    extra = [dict(max(g, key=lambda r: r["reward"]), strategy="BEST_FIXED") for g in groups.values()]
    return rows + extra


def paired_table(rows: list[dict], target: str = "LINUCB") -> list[dict]:
    index = {(r["seed"], r["scenario"], r["lambda_w"], r["strategy"]): r for r in rows}
    out = []
    keys = sorted({(r["scenario"], r["lambda_w"]) for r in rows})
    others = sorted({r["strategy"] for r in rows} - {target})
    for scenario, lam in keys:
        seeds = sorted({r["seed"] for r in rows if r["scenario"] == scenario and r["lambda_w"] == lam})
        for other in others:
            for metric in METRICS:
                diffs = []
                for s in seeds:
                    a, b = index.get((s, scenario, lam, target)), index.get((s, scenario, lam, other))
                    if a and b and a[metric] is not None and b[metric] is not None:
                        diffs.append(a[metric] - b[metric])
                if diffs:
                    mean, lo, hi = paired_ci(diffs)
                    out.append({"scenario": scenario, "lambda_w": lam, "comparison": f"{target} - {other}",
                                "metric": metric, "n_seeds": len(diffs), "mean_diff": mean,
                                "ci_low": lo, "ci_high": hi})
    return out


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scenarios", nargs="+", default=list(SCENARIOS))
    p.add_argument("--strategies", nargs="+", default=list(STRATEGIES))
    p.add_argument("--seeds", default="100-109", help="inclusive range, e.g. 100-199 (test seeds)")
    p.add_argument("--lambdas", nargs="+", type=float, default=[1.0])
    p.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    p.add_argument("--no-regret", action="store_true", help="skip per-meal regret (faster)")
    p.add_argument("--out", default=str(ROOT / "results"))
    p.add_argument("--beta", type=float, default=None, help="override learner beta (tune on seeds 0-29 only)")
    p.add_argument("--waste-tolerance", type=float, default=None, help="sensitivity analysis only")
    p.add_argument("--uptake-drop", type=float, default=None, help="sustained uptake-drop threshold; sensitivity only")
    args = p.parse_args()

    config = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
    seeds = parse_seeds(args.seeds)
    test_lo, test_hi = config["evaluation"]["seeds"]["test"]
    if not all(test_lo <= s <= test_hi for s in seeds):
        print("WARNING: seeds outside the held-out test range; do not report these as final results.")

    overrides = {}
    if args.beta is not None:
        overrides[("learner", "beta")] = args.beta
    if args.waste_tolerance is not None:
        overrides[("safety", "waste_tolerance")] = args.waste_tolerance
    if args.uptake_drop is not None:
        overrides[("safety", "uptake_sustained_drop")] = args.uptake_drop
    jobs = [(s, sc, st, lam, config, not args.no_regret, overrides)
            for sc in args.scenarios for lam in args.lambdas for s in seeds for st in args.strategies]
    print(f"Running {len(jobs)} semesters on {args.workers} worker(s)...")
    start = time.time()
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        rows = list(pool.map(run_one, jobs, chunksize=4))
    print(f"Done in {time.time() - start:.0f}s")

    rows = add_best_fixed(rows)
    paired = paired_table(rows)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    write_csv(out / "runs.csv", rows)
    write_csv(out / "paired.csv", paired)

    print("\nSIMULATED RESULTS. LINUCB minus baseline, mean over seeds [95% CI]")
    for row in paired:
        if row["metric"] in ("carbon", "waste_g", "reward", "dissatisfaction"):
            print(f"{row['scenario']:<22} lam={row['lambda_w']:<4} {row['comparison']:<22} "
                  f"{row['metric']:<16} {row['mean_diff']:+9.4f}  [{row['ci_low']:+9.4f}, {row['ci_high']:+9.4f}]")
    print(f"\nWrote {out / 'runs.csv'} and {out / 'paired.csv'}")


if __name__ == "__main__":
    main()