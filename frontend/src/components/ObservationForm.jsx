import { useState } from "react";
import { errorText } from "../format.js";
import { prettyDish } from "./Counter.jsx";
import { rememberName, rememberedName } from "./Candidates.jsx";

/**
 * After service: head count, total plate waste and portions taken of every dish. Also used for corrections,
 * where a reason is required. Waste is entered in kilograms (what the mess scale shows) and sent in grams.
 */
export default function ObservationForm({ meal, onSubmit, submitLabel, withReason = false, initial }) {
  const items = [...(meal.items || [])].sort((a, b) => a.standardSlot - b.standardSlot);
  const [attendance, setAttendance] = useState(initial?.actualAttendance ?? "");
  const [wasteKg, setWasteKg] = useState(initial ? String(Number(initial.aggregateWasteG) / 1000) : "");
  const [portions, setPortions] = useState(() => Object.fromEntries(items.map((i) => [i.id, ""])));
  const [who, setWho] = useState(rememberedName());
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      rememberName(who.trim());
      const body = {
        actualAttendance: Number(attendance),
        aggregateWasteG: Math.round(Number(wasteKg) * 1000 * 100) / 100,
        items: items.map((i) => ({ menuItemId: i.id, portionsTaken: Number(portions[i.id] || 0), wasteG: null })),
        enteredBy: who.trim(),
      };
      if (withReason) body.reason = reason.trim();
      await onSubmit(body);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="step-body" onSubmit={submit}>
      <div className="obs-grid">
        <label>Students who ate
          <input type="number" min="0" max={meal.expectedAttendance ? undefined : undefined} required value={attendance}
                 onChange={(e) => setAttendance(e.target.value)} />
        </label>
        <label>Plate waste, all bins (kg)
          <input type="number" min="0" step="0.01" required value={wasteKg} onChange={(e) => setWasteKg(e.target.value)} />
        </label>
      </div>
      <fieldset className="portions">
        <legend>Portions served</legend>
        {items.map((i) => (
          <label key={i.id}>
            {prettyDish(i.dishName)}
            <input type="number" min="0" required value={portions[i.id]}
                   onChange={(e) => setPortions({ ...portions, [i.id]: e.target.value })} />
          </label>
        ))}
      </fieldset>
      <div className="serve-row">
        <label>Entered by<input value={who} required maxLength={100} onChange={(e) => setWho(e.target.value)} placeholder="Your name" /></label>
        {withReason && (
          <label className="grow">What was wrong with the first entry?
            <input value={reason} required maxLength={500} onChange={(e) => setReason(e.target.value)}
                   placeholder="For example: scale was not zeroed" />
          </label>
        )}
        <button className="btn primary" disabled={busy}>{busy ? "Savingâ€¦" : submitLabel}</button>
      </div>
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </form>
  );
}