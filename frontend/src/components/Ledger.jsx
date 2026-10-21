import { mean, num, pct } from "../format.js";

/** How the semester compares with its own baseline weeks so far. */
export default function Ledger({ timeline }) {
  const scored = timeline.filter((r) => r.state === "EVALUATED" && r.evalStatus !== "EXCLUDED_ZERO_ATTENDANCE");
  const base = scored.filter((r) => r.phase === "BASELINE");
  const after = scored.filter((r) => r.phase !== "BASELINE");
  const c0 = mean(base.map((r) => r.carbonPerAttendee));
  const c1 = mean(after.map((r) => r.carbonPerAttendee));
  const w0 = mean(base.map((r) => r.wasteGPerAttendee));
  const w1 = mean(after.map((r) => r.wasteGPerAttendee));
  const rollbacks = after.filter((r) => r.rollback).length;
  const overrides = timeline.filter((r) => r.override).length;
  const evaluated = timeline.filter((r) => r.state === "EVALUATED").length;

  return (
    <section className="ledger" aria-labelledby="ledger-title">
      <h2 id="ledger-title">So far</h2>
      <dl>
        <div>
          <dt>Meals scored</dt>
          <dd>{evaluated}<small> of {timeline.length}</small></dd>
        </div>
        <div>
          <dt>Carbon per student</dt>
          <dd>{num(c1 ?? c0, 3)}<small> kg</small></dd>
          <p>{after.length && c0 ? `${pct(c1 / c0 - 1)} against the baseline weeks` : "Baseline weeks only"}</p>
        </div>
        <div>
          <dt>Plate waste per student</dt>
          <dd>{num(w1 ?? w0, 1)}<small> g</small></dd>
          <p>{after.length && w0 ? `${pct(w1 / w0 - 1)} against the baseline weeks` : "Baseline weeks only"}</p>
        </div>
        <div>
          <dt>Rollbacks</dt>
          <dd>{rollbacks}</dd>
          <p>{overrides} manual override{overrides === 1 ? "" : "s"}</p>
        </div>
      </dl>
    </section>
  );
}