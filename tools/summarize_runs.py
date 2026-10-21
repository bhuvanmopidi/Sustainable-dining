"""Per scenario x lambda x strategy: reward, policy shares, safety activity. Usage: python tools/summarize_runs.py results/x/runs.csv"""
import csv
import sys
from collections import defaultdict

rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
keep = ("FIXED_P0","FIXED_P3", "UCB1", "LINUCB", "LINUCB_NOSAFE", "BEST_FIXED")
fields = ("reward", "carbon", "waste_g", "uptake", "share_P0", "share_P3", "rollbacks", "safety_events")
groups = defaultdict(list)
for r in rows:
    if r["strategy"] in keep:
        groups[(r["scenario"], r["lambda_w"], r["strategy"])].append(r)

print(f"{'scenario':<22}{'lam':<5}{'strategy':<15}" + "".join(f"{f:>12}" for f in fields))
for (sc, lam, st), g in sorted(groups.items()):
    means = [sum(float(x[f]) for x in g) / len(g) for f in fields]
    print(f"{sc:<22}{lam:<5}{st:<15}" + "".join(f"{m:>12.4f}" for m in means))