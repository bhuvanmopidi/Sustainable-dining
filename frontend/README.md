# Mess counter dashboard (M8)

React + Vite. Talks to the Spring Boot backend on port 8080 through the Vite proxy (`/api`).

    npm install      # once
    npm run dev      # then open http://localhost:5173

Needs the backend running (and, for recommendations and scoring, the ML service on 8001;
for demo semesters, the simulator on 8002).

- Next meal: plan the menu, get the layout, serve it (or override with a reason), record what happened.
  Demo semesters are played by the simulator instead.
- Safety checks: open alerts, mark them as reviewed.
- The semester, meal by meal: nudge on the counter, carbon, plate waste and score, with one-week averages.
- Meal log: every served meal; corrections re-score that meal and all later ones.
- Import CSV (live semesters): menus and observations, with a check-only mode.