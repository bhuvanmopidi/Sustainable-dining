import { useState } from "react";
import { api } from "../api.js";
import { errorText } from "../format.js";

const SCENARIOS = [
  ["SUCCESSFUL_NUDGING", "Nudges work, little extra waste"],
  ["FAILED_SUBSTITUTION", "Nudged dishes get taken but wasted"],
  ["NUDGE_FATIGUE", "Nudges wear off with repetition"],
  ["CHANGING_PREFERENCES", "Tastes and attendance drift"],
  ["CONTEXT_DEPENDENT", "Nudges fail at dinner only"],
  ["AVOIDANCE_STRESS", "Strong nudges push students away"],
  ["NO_PLACEMENT_EFFECT", "Order makes no difference"],
];

export default function NewSemester({ onCreated }) {
  const [mode, setMode] = useState("SIMULATED_DEMO");
  const [name, setName] = useState("");
  const [startDate, setStartDate] = useState("2026-07-06");
  const [enrolled, setEnrolled] = useState(1000);
  const [seed, setSeed] = useState(100);
  const [scenario, setScenario] = useState("SUCCESSFUL_NUDGING");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const demo = mode === "SIMULATED_DEMO";

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = {
        name: name.trim() || (demo ? `Demo ${scenario.toLowerCase().replaceAll("_", " ")} ${seed}` : "Live semester"),
        mode,
        startDate: demo ? "2026-07-06" : startDate,
        enrolledStudents: demo ? 1000 : Number(enrolled),
      };
      if (demo) Object.assign(body, { simSeed: Number(seed), simScenario: scenario });
      onCreated(await api.createSemester(body));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel new-semester" onSubmit={submit}>
      <h2>New semester</h2>
      <div className="segmented" role="radiogroup" aria-label="Kind of semester">
        <button type="button" role="radio" aria-checked={demo} className={demo ? "seg on" : "seg"}
                onClick={() => setMode("SIMULATED_DEMO")}>Demo with the simulator</button>
        <button type="button" role="radio" aria-checked={!demo} className={!demo ? "seg on" : "seg"}
                onClick={() => setMode("LIVE")}>Live, run by the mess team</button>
      </div>
      <div className="fields">
        <label>Name<input value={name} onChange={(e) => setName(e.target.value)} placeholder="Optional" /></label>
        {demo ? (
          <>
            <label>Scenario
              <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
                {SCENARIOS.map(([k, text]) => <option key={k} value={k}>{text}</option>)}
              </select>
            </label>
            <label>Seed<input type="number" min="0" value={seed} onChange={(e) => setSeed(e.target.value)} /></label>
          </>
        ) : (
          <>
            <label>First day<input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} /></label>
            <label>Students enrolled
              <input type="number" min="1" value={enrolled} onChange={(e) => setEnrolled(e.target.value)} />
            </label>
          </>
        )}
      </div>
      {demo && <p className="hint">Demo semesters follow the simulator's calendar: 16 weeks from 6 July 2026, 1000 students.</p>}
      {error && <pre className="banner error">{errorText(error)}</pre>}
      <button className="btn primary" disabled={busy}>{busy ? "Creatingâ€¦" : "Create semester"}</button>
    </form>
  );
}