"""Check whether the reward saturates. Uses TUNING seed 0, not the test seeds."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from core.layout import policy_ordering  # noqa: E402
from core.reference import fit_reference  # noqa: E402
from eval_harness.runner import run_semester  # noqa: E402
from simulator.mess import MessSimulator  # noqa: E402

cfg = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))

for scenario in ("SUCCESSFUL_NUDGING", "FAILED_SUBSTITUTION"):
    sim, base = MessSimulator(0, scenario, cfg), []
    for t in range(42):
        spec = sim.meal_spec(t)
        obs, _ = sim.step(t, policy_ordering(spec["menu_items"], spec["slots"], 0.0))
        base.append({"meal": spec["meal"], "menu_items": spec["menu_items"], "observation": obs})
    ref = fit_reference(base, cfg, "diag")["reference_model"]
    print(f"\n{scenario}: s_c = {ref['s_c']:.4f} kg/attendee, s_w = {ref['s_w']:.2f} g/attendee, "
          f"w_ref lunch = {ref['params']['w_ref_g']['LUNCH']:.1f} g")
    for strategy in ("FIXED_P0", "FIXED_P3"):
        rows = [r for r in run_semester(0, scenario, strategy, cfg, compute_regret=False) if r["reward"] is not None]
        n = len(rows)
        print(f"  {strategy}: mean reward {sum(r['reward'] for r in rows) / n:+.3f}, "
              f"carbon {sum(r['carbon'] for r in rows) / n:.3f} kg, "
              f"waste {sum(r['waste_g'] for r in rows) / n:.1f} g, "
              f"clipped at +3: {100 * sum(r['reward'] >= 2.999 for r in rows) / n:.0f}% of meals")