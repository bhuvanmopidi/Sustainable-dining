# Self-correcting nudges for sustainable institutional dining

A working prototype of the paper *"A Closed-Loop Machine Learning Framework for Dynamic Choice Architecture:
Self-Correcting Nudges to Reduce Carbon Footprint and Plate Waste in Institutional Dining"*.

The system rearranges the serving counter of a hostel mess before every meal so students take more low-carbon
dishes. After each meal it measures carbon and plate waste, learns from the result (contextual bandit, LinUCB),
and automatically falls back to the standard order when a nudge starts causing waste.

**Results** (simulated mess, 1000 students, 100 held-out test semesters):

| Result | Value |
| --- | --- |
| Carbon per student vs. never nudging | 10–17% lower |
| Worst-case shortfall vs. best fixed policy in hindsight | LinUCB −0.050 · always-P3 −0.067 · always-P0 −0.152 |
| LinUCB vs. UCB1 and random rotation | better in 18 of 18 scenario × λ cases |
| No-placement-effect (null) scenario | difference exactly 0 |
| Demand forecast error (WAPE) | random forest 5–10% vs. seasonal-naive 10–37% |
| App vs. research code (parity check) | identical decisions on 336 of 336 meals, 2 scenarios |

All results come from simulation; carbon factors are illustrative placeholders. See [Limitations](#limitations).

---

## How it works

Each meal moves through five steps:

1. **Plan.** The manager saves the menu and expected head count.
2. **Recommend.** The ML service builds every distinct counter layout from four policies (P0 standard order …
   P3 lowest-carbon first), scores them with LinUCB (expected score + uncertainty bonus) and returns its choice.
3. **Serve.** The manager accepts the layout or overrides it with a written reason.
4. **Record.** After service: head count, total plate waste, portions taken of each dish.
5. **Score.** The meal's reward is relative carbon saved minus λ × relative extra waste, both measured against a
   frozen baseline model. Four safety checks (waste spike, sustained waste, uptake drop, sustained uptake drop)
   can force the next same-type meal back to the standard order and pause the nudges involved for three meals.

A semester is 16 weeks × 21 meals: 2 baseline weeks (standard order only), 1 warm-up week (every layout in
rotation), then 13 adaptive weeks.

## Architecture

```
 Browser                Java backend               Python
┌──────────────┐  HTTP  ┌──────────────────┐  HTTP  ┌──────────────────────┐
│ React        │ ─────▶ │ Spring Boot      │ ─────▶ │ ML service  :8001    │
│ dashboard    │        │ :8080            │        │ recommend, evaluate, │
│ :5173        │        │ workflow, rules, │        │ forecast, reference  │
└──────────────┘        │ CSV import,      │        └──────────────────────┘
                        │ corrections      │  HTTP  ┌──────────────────────┐
                        │                  │ ─────▶ │ Simulator    :8002   │
                        └────────┬─────────┘        │ (demo semesters)     │
                                 │ JDBC             └──────────────────────┘
                        ┌────────▼─────────┐        ┌──────────────────────┐
                        │ PostgreSQL :5432 │        │ Evaluation harness   │
                        └──────────────────┘        │ (offline, same core) │
                                                    └──────────────────────┘
```

- The learning code lives once in `python/core` and is used by both the ML service and the evaluation harness.
- The ML service is stateless: the backend sends the full learner log with every request, so the database is the
  single source of truth.
- Every Java ↔ Python message has a JSON Schema in `contracts/`, validated on both sides.
- Records are never deleted: corrections create a new observation revision and supersede old scores.

## Repository layout

```
config/            experiment configuration (immutable, versioned)
contracts/         JSON Schemas for every service message, samples, feature schema
python/
  core/            layouts, features, reward, reference model, safety rules, LinUCB, forecaster
  simulator/       simulated mess: 1000 students, 7 scenarios, keyed RNG
  eval_harness/    runs semesters for every strategy and seed
  ml_service/      FastAPI service used by the backend
  sim_service/     FastAPI wrapper around the simulator
  tests/           Python test suite
tools/             contract validation, result summaries, forecast evaluation, parity check, sample CSVs
backend/           Spring Boot 3 / Java 17 backend, Flyway migrations, tests
frontend/          React + Vite dashboard
results/           parity summary and experiment summaries
```

## Requirements

- Python 3.10+
- Java 17 and Maven 3.9
- PostgreSQL 14+ (tested on 18)
- Node.js 18+

## Setup

```bash
# Python
python -m venv .venv
.venv\Scripts\activate            # Windows (macOS/Linux: source .venv/bin/activate)
pip install -r python/requirements.txt

# PostgreSQL: create a user and two databases
psql -U postgres -c "CREATE USER dining WITH PASSWORD 'dining';"
psql -U postgres -c "CREATE DATABASE dining OWNER dining;"
psql -U postgres -c "CREATE DATABASE dining_test OWNER dining;"

# Frontend
cd frontend && npm install
```

The database credentials above are local development defaults (`backend/src/main/resources/application.yml`).
Change them for any shared deployment.

## Running

Start four processes, each in its own terminal, from the repository root:

```bash
python -m uvicorn ml_service.app:app --app-dir python --port 8001
python -m uvicorn sim_service.app:app --app-dir python --port 8002
cd backend && mvn spring-boot:run
cd frontend && npm run dev            # open http://localhost:5173
```

Flyway creates the schema on first start. In the dashboard, create a **demo semester** (played by the simulator)
or a **live semester** (run meal by meal, or filled from CSV).

To try CSV import with simulator-generated data:

```bash
python tools/make_sample_csv.py       # writes samples/menus.csv and samples/observations.csv
```

Then create a live semester starting 2026-07-06 with 1000 students and import both files from the
**Import CSV** tab.

## Tests

```bash
python -m unittest discover -s python/tests     # 127 tests
cd backend && mvn test                          # 58 tests
```

## Reproducing the results

```bash
# full test run: 100 seeds, all scenarios and strategies, three values of λ
python python/eval_harness/experiment.py --seeds 100-199 --lambdas 0.5 1.0 2.0 --out results/final
python tools/summarize_runs.py results/final/runs.csv > results/final/summary.txt

# forecaster accuracy
python tools/evaluate_forecast.py

# parity: the full app vs. the harness (needs all four services running)
python tools/parity_check.py
```

Seeds 0–29 were used for tuning, 30–99 for validation and 100–199 for the reported results.

## Limitations

- All results come from a simulated mess; its behaviour (placement effect, waste of nudged food) is an assumption.
- Dish carbon factors are illustrative, not taken from a life-cycle database.
- Seeds 100–109 were inspected during development.
- The method never beats the best fixed policy chosen in hindsight; its value is robustness across scenarios.
- Safety checks also fire on noise, which costs some reward; the version without them scores better in the worst case.
- The reward can favour students skipping a meal; the uptake checks catch only large drops.
- The app refits the forecaster on every meal, so late demo meals take 3–4 seconds each.

## Author

Mopidi Bhuvan, B.Tech CSE, VIT Vellore.
Mohammad Bilal,B.Tech CSE, VIT Vellore.
