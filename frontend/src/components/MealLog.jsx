import { useEffect, useState } from "react";
import { api } from "../api.js";
import { MEAL_NAMES, PHASE_NAMES, errorText, formatDate, num, signed } from "../format.js";
import ObservationForm from "./ObservationForm.jsx";

/** Served meals, newest first. A wrong entry can be corrected; later meals are then re-scored. */
export default function MealLog({ semester, timeline, onChange }) {
  const [limit, setLimit] = useState(30);
  const [open, setOpen] = useState(null);
  const [note, setNote] = useState(null);
  const served = timeline.filter((r) => r.state === "EVALUATED").reverse();

  if (!served.length) {
    return <p className="hint log-empty">Served meals appear here once they are scored.</p>;
  }
  return (
    <div className="log">
      {note && <p className="result" role="status">{note}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Meal</th>
              <th scope="col">Date</th>
              <th scope="col">Week</th>
              <th scope="col">Served</th>
              <th scope="col" className="r">Score</th>
              <th scope="col" className="r">Carbon kg</th>
              <th scope="col" className="r">Waste g</th>
              <th scope="col">Checks</th>
              <th scope="col"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {served.slice(0, limit).map((r) => (
              <Row key={r.mealId} r={r} semester={semester} open={open === r.mealId}
                   onToggle={() => setOpen(open === r.mealId ? null : r.mealId)}
                   onCorrected={(res) => {
                     setOpen(null);
                     setNote(`Meal ${res.mealIndex + 1} corrected (entry ${res.revision}). ${res.replayedMeals} meal${res.replayedMeals === 1 ? "" : "s"} re-scored, ${res.changes.length} changed.`);
                     onChange();
                   }} />
            ))}
          </tbody>
        </table>
      </div>
      {served.length > limit && <button className="btn quiet" onClick={() => setLimit(limit + 60)}>Show older meals</button>}
    </div>
  );
}

function Row({ r, semester, open, onToggle, onCorrected }) {
  return (
    <>
      <tr className={r.rollback ? "row-rollback" : undefined}>
        <td>{r.mealIndex + 1}. {MEAL_NAMES[r.mealType]}</td>
        <td>{formatDate(r.date)}</td>
        <td>{PHASE_NAMES[r.phase]}</td>
        <td>{r.labels.join(" = ")}{r.override ? " (override)" : ""}{r.source === "ROLLBACK" ? " (rollback)" : ""}</td>
        <td className="r">{r.reward === null ? "â€“" : signed(r.reward)}</td>
        <td className="r">{num(r.carbonPerAttendee, 3)}</td>
        <td className="r">{num(r.wasteGPerAttendee, 1)}</td>
        <td>{r.safetyEvents ? `${r.safetyEvents}${r.rollback ? ", rolled back" : ""}` : ""}</td>
        <td><button className="btn quiet small" aria-expanded={open} onClick={onToggle}>{open ? "Cancel" : "Correct"}</button></td>
      </tr>
      {open && (
        <tr className="row-form">
          <td colSpan={9}><Correction row={r} semester={semester} onCorrected={onCorrected} /></td>
        </tr>
      )}
    </>
  );
}

function Correction({ row, semester, onCorrected }) {
  const [state, setState] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    Promise.all([api.meal(row.mealId), api.observations(row.mealId)])
      .then(([meal, history]) => live && setState({ meal, current: history.find((h) => h.current) }))
      .catch((e) => live && setError(e));
    return () => {
      live = false;
    };
  }, [row.mealId]);
  if (error) return <pre className="banner error">{errorText(error)}</pre>;
  if (!state) return <p className="hint">Loading the entryâ€¦</p>;
  return (
    <div className="correction">
      <p className="hint">
        First entry: {state.current?.actualAttendance} students, {num(Number(state.current?.aggregateWasteG) / 1000, 2)} kg waste,
        entered by {state.current?.enteredBy}. Enter the right numbers for every dish. This meal and every later
        meal are re-scored; the layouts already served stay as they were.
        {semester.referenceFitted && row.phase === "BASELINE" && " Baseline meals cannot be corrected once the reference is fitted."}
      </p>
      <ObservationForm meal={state.meal} withReason submitLabel="Save correction" initial={state.current}
                       onSubmit={async (body) => onCorrected(await api.correct(row.mealId, body))} />
    </div>
  );
}