import { useCallback, useEffect, useState } from "react";
import { api } from "./api.js";
import { errorText, formatDate } from "./format.js";
import NewSemester from "./components/NewSemester.jsx";
import NextMeal from "./components/NextMeal.jsx";
import Alerts from "./components/Alerts.jsx";
import Ledger from "./components/Ledger.jsx";
import Charts from "./components/Charts.jsx";
import MealLog from "./components/MealLog.jsx";
import ImportPanel from "./components/ImportPanel.jsx";

const STORE_KEY = "dining.semester";

function remembered() {
  try {
    return localStorage.getItem(STORE_KEY);
  } catch {
    return null;
  }
}

export default function App() {
  const [semesters, setSemesters] = useState(null);
  const [selectedId, setSelectedId] = useState(remembered());
  const [creating, setCreating] = useState(false);
  const [version, setVersion] = useState(0);
  const [timeline, setTimeline] = useState([]);
  const [tab, setTab] = useState("log");
  const [error, setError] = useState(null);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    api.semesters()
      .then((list) => {
        setSemesters(list);
        setError(null);
        if (list.length && !list.some((s) => s.id === selectedId)) setSelectedId(list[0].id);
      })
      .catch(setError);
  }, [version]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedId) return;
    try {
      localStorage.setItem(STORE_KEY, selectedId);
    } catch {
      /* private window: just don't remember */
    }
    api.timeline(selectedId).then(setTimeline).catch(setError);
  }, [selectedId, version]);

  const semester = semesters?.find((s) => s.id === selectedId) || null;

  return (
    <div className="page">
      <header className="masthead">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <div>
            <h1>Mess counter</h1>
            <p className="brand-sub">Self-correcting serving order for lower-carbon, lower-waste meals</p>
          </div>
        </div>
        <div className="semester-pick">
          <label htmlFor="semester">Semester</label>
          <select id="semester" value={selectedId || ""} onChange={(e) => setSelectedId(e.target.value)}
                  disabled={!semesters?.length}>
            {!semesters?.length && <option value="">No semesters yet</option>}
            {semesters?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name} ({s.mode === "LIVE" ? "live" : `demo, ${s.simScenario.toLowerCase().replaceAll("_", " ")}`})
              </option>
            ))}
          </select>
          <button className="btn quiet" onClick={() => setCreating((c) => !c)}>
            {creating ? "Close" : "New semester"}
          </button>
        </div>
      </header>

      {error && <pre className="banner error">{errorText(error)}</pre>}

      {creating && (
        <NewSemester
          onCreated={(s) => {
            setCreating(false);
            setSelectedId(s.id);
            refresh();
          }}
        />
      )}

      {semesters && !semesters.length && !creating && (
        <section className="empty">
          <h2>Start with a semester</h2>
          <p>
            A live semester is run by the mess team meal by meal. A demo semester plays the meals against the
            simulator, which is the quickest way to see the whole loop.
          </p>
          <button className="btn primary" onClick={() => setCreating(true)}>New semester</button>
        </section>
      )}

      {semester && (
        <>
          <p className="semester-line">
            {semester.name}: {semester.mealCount} meals from {formatDate(semester.startDate)},{" "}
            {semester.enrolledStudents} students enrolled.{" "}
            {semester.referenceFitted ? "Baseline reference fitted." : "Baseline still being collected."}
          </p>
          <div className="grid">
            <NextMeal semester={semester} version={version} onChange={refresh} timeline={timeline} />
            <aside className="side">
              <Alerts semester={semester} version={version} onChange={refresh} />
              <Ledger timeline={timeline} />
            </aside>
          </div>
          <Charts timeline={timeline} />
          <section className="lower">
            <div className="tabs" role="tablist">
              <button role="tab" aria-selected={tab === "log"} className={tab === "log" ? "tab on" : "tab"}
                      onClick={() => setTab("log")}>Meal log</button>
              {semester.mode === "LIVE" && (
                <button role="tab" aria-selected={tab === "import"} className={tab === "import" ? "tab on" : "tab"}
                        onClick={() => setTab("import")}>Import CSV</button>
              )}
            </div>
            {tab === "log" || semester.mode !== "LIVE"
              ? <MealLog semester={semester} timeline={timeline} onChange={refresh} />
              : <ImportPanel semester={semester} onChange={refresh} />}
          </section>
        </>
      )}
    </div>
  );
}