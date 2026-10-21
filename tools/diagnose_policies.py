"""Which fixed policy is best, per scenario, meal type and lambda? TUNING seeds 0-2 only."""
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from eval_harness.runner import run_semester  # noqa: E402
from simulator.scenarios import SCENARIOS  # noqa: E402

cfg = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
SEEDS, LAMBDAS, LABELS = (0, 1, 2), (0.5, 1.0, 2.0), ("P0", "P1", "P2", "P3")
cache = {}


def rows(scenario, label, lam, seed):
    key = (scenario, label, lam, seed)
    if key not in cache:
        c = copy.deepcopy(cfg)
        c["reward"]["lambda_w"] = lam
        cache[key] = run_semester(seed, scenario, f"FIXED_{label}", c, compute_regret=False)
    return cache[key]


print("Mean reward of each FIXED policy after the baseline (relative scale). * = best")
for scenario in SCENARIOS:
    print(f"\n{scenario}")
    for lam in LAMBDAS:
        parts = []
        for mt in ("BREAKFAST", "LUNCH", "DINNER"):
            means = {}
            for lbl in LABELS:
                vals = [r["reward"] for s in SEEDS for r in rows(scenario, lbl, lam, s)
                        if r["meal_type"] == mt and r["reward"] is not None]
                means[lbl] = sum(vals) / len(vals)
            best = max(means, key=means.get)
            parts.append(f"{mt[:1]}: " + " ".join(f"{lbl}{'*' if lbl == best else ' '}{means[lbl]:+.3f}"
                                                   for lbl in LABELS))
        print(f"  lambda={lam:<4} " + " | ".join(parts))