import { useState } from "react";
import { api } from "../api.js";
import { MEAL_NAMES, errorText, formatDate, signed } from "../format.js";

const KINDS = {
  menus: {
    title: "Menus",
    columns: "date, meal_type, dish, slot, expected_attendance, and optionally role",
    example: "2026-07-06,LUNCH,Dal Tadka,1,820,",
  },
  observations: {
    title: "What was served",
    columns: "date, meal_type, dish, portions_taken, attendance, total_waste_g, and optionally waste_g",
    example: "2026-07-06,LUNCH,Dal Tadka,212,,804,23150",
  },
};

/** Bulk entry from spreadsheets. Check a file first; nothing is saved unless the whole file is valid. */
export default function ImportPanel({ semester, onChange }) {
  return (
    <div className="imports">
      <ImportBox semester={semester} kind="menus" onChange={onChange} />
      <ImportBox semester={semester} kind="observations" onChange={onChange} />
    </div>
  );
}

function ImportBox({ semester, kind, onChange }) {
  const meta = KINDS[kind];
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  async function send(dryRun) {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const res = await api.importCsv(semester.id, kind, file, dryRun, "csv-import");
      setResult(res);
      if (!dryRun) onChange();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="import-box">
      <h3>{meta.title}</h3>
      <p className="hint">One row per dish per meal. Columns: {meta.columns}.</p>
      <code className="example">{meta.example}</code>
      {kind === "observations" && (
        <p className="hint">Baseline meals are served in the standard order automatically. Later meals need their layout chosen on this page first.</p>
      )}
      <input type="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] || null)}
             aria-label={`${meta.title} CSV file`} />
      <div className="serve-row">
        <button className="btn" disabled={!file || busy} onClick={() => send(true)}>Check the file</button>
        <button className="btn primary" disabled={!file || busy} onClick={() => send(false)}>
          {busy ? "Importingâ€¦" : "Import"}
        </button>
      </div>
      {result && (
        <div className="result" role="status">
          <p>{result.dryRun ? "The file is valid. Nothing was saved." : "Imported."} {result.rowsRead} rows, {result.mealsAffected} meals.</p>
          <ul className="import-list">
            {result.meals.slice(0, 8).map((m) => (
              <li key={m.mealIndex}>
                {MEAL_NAMES[m.mealType]}, {formatDate(m.date)}{m.reward !== null && m.reward !== undefined ? `: score ${signed(m.reward)}` : ""}
                {m.safetyEvents ? `, ${m.safetyEvents} safety check${m.safetyEvents > 1 ? "s" : ""}` : ""}
              </li>
            ))}
            {result.meals.length > 8 && <li>and {result.meals.length - 8} more</li>}
          </ul>
        </div>
      )}
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </div>
  );
}