"""Dish catalogue and weekly menu rotation.

Carbon factors (kg CO2e per serving) and preferences are illustrative simulation
assumptions, not sourced measurements. Replace them with literature values before
reporting results.
"""
import statistics
import uuid
from dataclasses import dataclass

DISH_NAMESPACE = uuid.UUID("0b6f3c1e-5a2d-4e8f-9c71-3d2a1f6e8b40")


@dataclass(frozen=True)
class Dish:
    name: str
    category: str
    carbon_kg: float     # per serving
    portion_g: float
    preference: float    # LATENT: mean intrinsic utility; never leaves the simulator
    role: str            # ELIGIBLE or FIXED


_ROWS = [
    # name             category   kgCO2e  grams  pref  role
    ("chicken_curry",  "non_veg",  1.60,  150,  1.00, "ELIGIBLE"),
    ("mutton_curry",   "non_veg",  3.90,  150,  1.20, "ELIGIBLE"),
    ("fish_curry",     "non_veg",  1.10,  150,  0.40, "ELIGIBLE"),
    ("egg_curry",      "egg",      0.80,  150,  0.50, "ELIGIBLE"),
    ("paneer_masala",  "paneer",   1.20,  150,  0.90, "ELIGIBLE"),
    ("dal_tadka",      "pulse",    0.35,  180,  0.30, "ELIGIBLE"),
    ("chana_masala",   "pulse",    0.30,  180,  0.35, "ELIGIBLE"),
    ("rajma",          "pulse",    0.32,  180,  0.40, "ELIGIBLE"),
    ("veg_kurma",      "veg",      0.40,  170,  0.10, "ELIGIBLE"),
    ("idli_sambar",    "pulse",    0.30,  200,  0.50, "ELIGIBLE"),
    ("poha",           "veg",      0.20,  180,  0.30, "ELIGIBLE"),
    ("upma",           "veg",      0.22,  180,  0.00, "ELIGIBLE"),
    ("masala_dosa",    "veg",      0.35,  180,  0.90, "ELIGIBLE"),
    ("aloo_paratha",   "veg",      0.45,  160,  0.80, "ELIGIBLE"),
    ("egg_bhurji",     "egg",      0.75,  150,  0.70, "ELIGIBLE"),
    ("bread_omelette", "egg",      0.85,  160,  0.60, "ELIGIBLE"),
    ("rice",           "staple",   0.25,  200,  0.00, "FIXED"),
    ("chapati",        "staple",   0.08,   40,  0.00, "FIXED"),
    ("bread",          "staple",   0.06,   30,  0.00, "FIXED"),
    ("tea",            "beverage", 0.20,  150,  0.00, "FIXED"),
]
DISHES = {r[0]: Dish(*r) for r in _ROWS}

LOW_CARBON_CATEGORIES = {"pulse", "veg"}
HIGH_CARBON_CATEGORIES = {"non_veg", "paneer"}

FIXED_RATES = {"rice": 0.90, "chapati": 2.5, "bread": 2.0, "tea": 1.0}   # portions per attendee
FIXED_BY_MEAL = {"BREAKFAST": ["tea", "bread"], "LUNCH": ["rice", "chapati"], "DINNER": ["rice", "chapati"]}

# One week, repeated. Every dish appears at least once a week, so the 2-week baseline sees each one twice.
WEEKLY_MENUS = {
    "BREAKFAST": [
        ["idli_sambar", "egg_bhurji", "poha"], ["masala_dosa", "bread_omelette", "upma"],
        ["aloo_paratha", "egg_bhurji", "idli_sambar"], ["poha", "bread_omelette", "masala_dosa"],
        ["upma", "egg_bhurji", "aloo_paratha"], ["masala_dosa", "idli_sambar", "bread_omelette"],
        ["aloo_paratha", "poha", "egg_bhurji"],
    ],
    "LUNCH": [
        ["chicken_curry", "dal_tadka", "veg_kurma"], ["paneer_masala", "rajma", "dal_tadka"],
        ["fish_curry", "chana_masala", "veg_kurma"], ["egg_curry", "dal_tadka", "rajma"],
        ["chicken_curry", "paneer_masala", "chana_masala"], ["mutton_curry", "dal_tadka", "veg_kurma"],
        ["paneer_masala", "chana_masala", "rajma"],
    ],
    "DINNER": [
        ["egg_curry", "chana_masala", "dal_tadka"], ["chicken_curry", "veg_kurma", "rajma"],
        ["paneer_masala", "dal_tadka", "chana_masala"], ["chicken_curry", "rajma", "veg_kurma"],
        ["fish_curry", "paneer_masala", "dal_tadka"], ["egg_curry", "veg_kurma", "chana_masala"],
        ["mutton_curry", "paneer_masala", "dal_tadka"],
    ],
}

_CMIN = min(d.carbon_kg for d in DISHES.values())
_CMAX = max(d.carbon_kg for d in DISHES.values())


def dish_id(name: str) -> str:
    return str(uuid.uuid5(DISH_NAMESPACE, name))


def carbon_score(dish: Dish) -> float:
    """Normalised against the fixed catalogue range, not the current meal."""
    return round((dish.carbon_kg - _CMIN) / (_CMAX - _CMIN), 6)


def catalogue_median_score() -> float:
    return statistics.median(carbon_score(d) for d in DISHES.values())