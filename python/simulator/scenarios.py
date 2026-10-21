"""Behavioural scenarios. These are simulation assumptions, not empirical findings."""
from dataclasses import dataclass, field


def _same(v):
    return {"BREAKFAST": v, "LUNCH": v, "DINNER": v}


@dataclass(frozen=True)
class Scenario:
    name: str
    placement_beta: float = 1.0          # utility gained per unit of slot visibility
    substitution_waste: dict = field(default_factory=lambda: _same(0.10))  # extra waste fraction when taking a less-liked dish
    fatigue_rate: float = 0.0            # placement effect decays with recent nudge exposure
    pref_drift: float = 0.0              # low-carbon preference rises / high-carbon falls over the semester
    attendance_drift: float = 0.0        # fractional attendance decline by semester end
    avoidance: float = 0.0               # opt-out utility added per unit of D (students skip the main)


SCENARIOS = {
    "NO_PLACEMENT_EFFECT": Scenario("NO_PLACEMENT_EFFECT", placement_beta=0.0),
    "SUCCESSFUL_NUDGING": Scenario("SUCCESSFUL_NUDGING", placement_beta=1.2, substitution_waste=_same(0.05)),
    "FAILED_SUBSTITUTION": Scenario("FAILED_SUBSTITUTION", placement_beta=1.5, substitution_waste=_same(0.45)),
    "NUDGE_FATIGUE": Scenario("NUDGE_FATIGUE", placement_beta=1.2, fatigue_rate=0.25),
    "CHANGING_PREFERENCES": Scenario("CHANGING_PREFERENCES", placement_beta=1.0, substitution_waste=_same(0.15),
                                     pref_drift=0.6, attendance_drift=0.10),
    "CONTEXT_DEPENDENT": Scenario("CONTEXT_DEPENDENT", placement_beta=1.2,
                                  substitution_waste={"BREAKFAST": 0.05, "LUNCH": 0.05, "DINNER": 0.50}),
    "AVOIDANCE_STRESS": Scenario("AVOIDANCE_STRESS", placement_beta=1.2, avoidance=8.0),
}