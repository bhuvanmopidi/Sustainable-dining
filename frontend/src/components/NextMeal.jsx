import { useEffect, useRef, useState } from "react";
import { api } from "../api.js";
import { MEAL_NAMES, PHASE_NAMES, errorText, formatDate, num, policyText, signed } from "../format.js";
import Counter, { CarbonKey } from "./Counter.jsx";
import PlanMenu from "./PlanMenu.jsx";
import Candidates, { reasonText } from "./Candidates.jsx";
import ObservationForm from "./ObservationForm.jsx";

const STEPS = ["Plan the menu", "Get the layout", "Serve", "Record what happened", "Scored"];

function stepOf(meal) {
  if (!meal) return 4;
  if (meal.state === "PLANNED") return meal.items.length ? 1 : 0;
  return { RECOMMENDED: 2, DEPLOYED: 3, OBSERVED: 3, EVALUATED: 4 }[meal.state];
}

/** The meal the team should work on now, and the one action that moves it forward. */
export default function NextMeal({ semester, version, onChange, timeline }) {
  const [meal, setMeal] = useState(null);
  const [complete, setComplete] = useState(false);
  const [rec, setRec] = useState(null);
  const [result, setResult] = useState(null);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    setError(null);
    api.nextMeal(semester.id)
      .then((m) => {
        if (!live) return;
        setComplete(false);
        setMeal(m);
        if (m.state === "RECOMMENDED" || m.state === "DEPLOYED") {
          api.recommendation(m.id).then((r) => live && setRec(r)).catch(setError);
        } else {
          setRec(null);
        }
      })
      .catch((e) => {
        if (!live) return;
        if (e.code === "SEMESTER_COMPLETE") {
          setComplete(true);
          setMeal(null);
        } else setError(e);
      });
    return () => {
      live = false;
    };
  }, [semester.id, version]);

  async function act(fn) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      onChange();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  const demo = semester.mode === "SIMULATED_DEMO";
  const step = stepOf(meal);

  return (
    <section className="panel next-meal" aria-labelledby="next-meal-title">
      {complete ? (
        <>
          <h2 id="next-meal-title">Semester complete</h2>
          <p>Every meal has been served and scored. The charts below show how the semester went.</p>
        </>
      ) : !meal ? (
        <p className="hint">{error ? "" : "Loading the next mealâ€¦"}</p>
      ) : (
        <>
          <div className="meal-title">
            <h2 id="next-meal-title">{MEAL_NAMES[meal.mealType]}, {formatDate(meal.date)}</h2>
            <span className={`phase phase-${meal.phase.toLowerCase()}`}>{PHASE_NAMES[meal.phase]} week</span>
            <span className="meal-no">Meal {meal.mealIndex + 1} of {semester.mealCount}</span>
          </div>
          {meal.forceP0 && (
            <p className="banner warn">The last {MEAL_NAMES[meal.mealType].toLowerCase()} tripped a safety check, so this one goes back to the standard order.</p>
          )}

          {demo ? (
            <DemoRunner semester={semester} onChange={onChange} timeline={timeline} />
          ) : (
            <>
              <ol className="steps" aria-label="Progress for this meal">
                {STEPS.map((s, i) => (
                  <li key={s} className={i < step ? "done" : i === step ? "now" : ""} aria-current={i === step ? "step" : undefined}>
                    <span className="step-no">{i + 1}</span>{s}
                  </li>
                ))}
              </ol>

              {step === 0 && <PlanMenu meal={meal} semester={semester} onSaved={onChange} />}

              {step === 1 && !editing && (
                <div className="step-body">
                  <p className="hint">Menu saved for {meal.expectedAttendance} students. Ask for today's layout when the dishes are ready to go out.</p>
                  <Counter dishOrder={meal.items.filter((i) => i.role === "ELIGIBLE")
                                       .sort((a, b) => a.standardSlot - b.standardSlot).map((i) => i.dishName)}
                           items={meal.items} slots={semester.slots} />
                  <CarbonKey />
                  <div className="serve-row">
                    <button className="btn primary" disabled={busy} onClick={() => act(() => api.recommend(meal.id))}>
                      {busy ? "Working out the layoutâ€¦" : "Get today's layout"}
                    </button>
                    <button className="btn quiet" onClick={() => setEditing(true)}>Change the menu</button>
                  </div>
                </div>
              )}
              {step === 1 && editing && (
                <PlanMenu meal={meal} semester={semester} onSaved={() => { setEditing(false); onChange(); }} />
              )}

              {step === 2 && rec && <Candidates meal={meal} semester={semester} rec={rec} onDeployed={onChange} />}

              {step === 3 && meal.state === "DEPLOYED" && (
                <>
                  <Served meal={meal} semester={semester} rec={rec} timeline={timeline} />
                  <ObservationForm meal={meal} submitLabel="Save and score this meal"
                                   onSubmit={async (body) => {
                                     setResult(await api.observe(meal.id, body));
                                     onChange();
                                   }} />
                </>
              )}
              {step === 3 && meal.state === "OBSERVED" && (
                <div className="step-body">
                  <p className="banner warn">The numbers are saved but the meal could not be scored, usually because the ML service was not running.</p>
                  <button className="btn primary" disabled={busy}
                          onClick={() => act(async () => setResult(await api.evaluate(meal.id)))}>Score it now</button>
                </div>
              )}
            </>
          )}
        </>
      )}
      {result && !demo && <ResultNote result={result} onClose={() => setResult(null)} />}
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </section>
  );
}

function Served({ meal, semester, rec, timeline }) {
  const row = timeline.find((r) => r.mealId === meal.id);
  const deployed = rec?.candidates.find((c) => row && sameLabels(c.labels, row.labels)) ||
    rec?.candidates.find((c) => c.recommended);
  if (!deployed) return null;
  return (
    <div className="served">
      <p className="hint">On the counter now: {policyText(deployed.labels)}. Fill this in after service.</p>
      <Counter dishOrder={deployed.dishOrder} items={meal.items} slots={semester.slots} compact />
    </div>
  );
}

function sameLabels(a, b) {
  return [...a].sort().join() === [...b].sort().join();
}

function ResultNote({ result, onClose }) {
  const events = result.safetyEvents || [];
  return (
    <div className={events.length ? "result warnish" : "result"} role="status">
      <p>
        Meal {result.mealIndex + 1} scored{result.reward !== null ? ` ${signed(result.reward)}` : " (baseline, not scored yet)"}.
        {" "}Carbon {num(result.carbonPerAttendee, 3)} kg and waste {num(result.wasteGPerAttendee, 1)} g per student.
        {events.length > 0 && ` ${events.length} safety check${events.length > 1 ? "s" : ""} tripped; see the alerts.`}
        {result.referenceFitted && result.evalStatus === "REFERENCE_INCOMPLETE" && " Baseline reference is now fitted."}
      </p>
      <button className="btn quiet small" onClick={onClose}>Dismiss</button>
    </div>
  );
}

/** Demo semesters: the simulator plays the mess, so the only control is how many meals to run. */
function DemoRunner({ semester, onChange, timeline }) {
  const [running, setRunning] = useState(0);
  const [done, setDone] = useState(0);
  const [error, setError] = useState(null);
  const stop = useRef(false);

  async function run(n) {
    stop.current = false;
    setRunning(n);
    setDone(0);
    setError(null);
    try {
      for (let i = 0; i < n && !stop.current; i++) {
        await api.runDemo(semester.id, 1);
        setDone(i + 1);
        if (i % 3 === 2 || i === n - 1) onChange();
      }
    } catch (e) {
      if (e.code !== "SEMESTER_COMPLETE") setError(e);
    } finally {
      setRunning(0);
      onChange();
    }
  }

  const lastRow = [...timeline].reverse().find((r) => r.state === "EVALUATED");
  return (
    <div className="step-body">
      <p className="hint">The simulator plans each menu, serves the chosen layout to 1000 simulated students and reports what they took and wasted.</p>
      <div className="serve-row">
        {running ? (
          <>
            <progress max={running} value={done} aria-label="Meals run" />
            <span>{done} of {running} meals</span>
            <button className="btn quiet" onClick={() => { stop.current = true; }}>Stop after this meal</button>
          </>
        ) : (
          <>
            <button className="btn primary" onClick={() => run(1)}>Run the next meal</button>
            <button className="btn" onClick={() => run(3)}>Run a day</button>
            <button className="btn" onClick={() => run(21)}>Run a week</button>
          </>
        )}
      </div>
      {lastRow && <LastServed semester={semester} row={lastRow} />}
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </div>
  );
}

function LastServed({ semester, row }) {
  const [view, setView] = useState(null);
  useEffect(() => {
    let live = true;
    Promise.all([api.meal(row.mealId), api.recommendation(row.mealId)])
      .then(([meal, rec]) => live && setView({ meal, rec }))
      .catch(() => live && setView(null));
    return () => {
      live = false;
    };
  }, [row.mealId]);
  if (!view) return null;
  const served = view.rec.candidates.find((c) => sameLabels(c.labels, row.labels)) || view.rec.candidates.find((c) => c.recommended);
  return (
    <div className="served">
      <h3>Last served: {MEAL_NAMES[row.mealType].toLowerCase()} on {formatDate(row.date)}</h3>
      <p className="reason">{reasonText(view.rec.reasonCode)}</p>
      <Counter dishOrder={served.dishOrder} items={view.meal.items} slots={semester.slots} />
      <CarbonKey />
      <p className="hint">
        {policyText(row.labels)}. Score {row.reward === null ? "not scored (baseline)" : signed(row.reward)},
        carbon {num(row.carbonPerAttendee, 3)} kg and waste {num(row.wasteGPerAttendee, 1)} g per student
        {row.safetyEvents ? `, ${row.safetyEvents} safety check${row.safetyEvents > 1 ? "s" : ""} tripped` : ""}.
      </p>
    </div>
  );
}