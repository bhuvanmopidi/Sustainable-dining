import { useEffect, useState } from "react";
import { api } from "../api.js";
import { errorText } from "../format.js";
import { prettyDish } from "./Counter.jsx";

/** Choose a dish for each counter slot and the expected head count, then save the menu. */
export default function PlanMenu({ meal, semester, onSaved }) {
  const slots = [...semester.slots].sort((a, b) => a.slotIndex - b.slotIndex);
  const [dishes, setDishes] = useState([]);
  const [choice, setChoice] = useState(() => {
    const c = {};
    for (const it of meal.items || []) c[it.standardSlot] = it.dishId;
    return c;
  });
  const [expected, setExpected] = useState(meal.expectedAttendance || Math.round(semester.enrolledStudents * 0.8));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.dishes().then((list) => setDishes([...list].sort((a, b) => a.name.localeCompare(b.name)))).catch(setError);
  }, []);

  async function save(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const items = slots
        .filter((s) => choice[s.slotIndex])
        .map((s) => ({ dishId: choice[s.slotIndex], role: s.eligibleSlot ? "ELIGIBLE" : "FIXED", standardSlot: s.slotIndex }));
      await api.plan(meal.id, { expectedAttendance: Number(expected), items });
      onSaved();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="step-body" onSubmit={save}>
      <p className="hint">
        Put the dishes in their usual order. Slot 1 is what students reach first. Curries and mains go in the
        counter slots; staples like rice and tea go in the fixed slots, which never move.
      </p>
      <div className="plan-grid">
        {slots.map((s) => (
          <label key={s.slotIndex}>
            {s.eligibleSlot ? `Slot ${s.slotIndex}` : `Fixed slot ${s.slotIndex}`}
            <select value={choice[s.slotIndex] || ""}
                    onChange={(e) => setChoice({ ...choice, [s.slotIndex]: e.target.value })}>
              <option value="">{s.eligibleSlot ? "Choose a dish" : "Nothing here"}</option>
              {dishes.filter((d) => d.eligibleByDefault === s.eligibleSlot || !s.eligibleSlot).map((d) => (
                <option key={d.id} value={d.id}>{prettyDish(d.name)} ({Number(d.carbonKgPerServing).toFixed(2)} kg)</option>
              ))}
            </select>
          </label>
        ))}
        <label>
          Expected students
          <input type="number" min="0" max={semester.enrolledStudents} value={expected}
                 onChange={(e) => setExpected(e.target.value)} />
        </label>
      </div>
      {!dishes.length && !error && <p className="hint">No dishes in the catalogue yet. Add dishes through the API or import a menu CSV.</p>}
      {error && <pre className="banner error">{errorText(error)}</pre>}
      <button className="btn primary" disabled={busy}>{busy ? "Savingâ€¦" : "Save menu"}</button>
    </form>
  );
}