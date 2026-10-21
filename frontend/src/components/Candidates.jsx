import { useState } from "react";
import { api } from "../api.js";
import { errorText, num, policyText } from "../format.js";
import Counter, { CarbonKey } from "./Counter.jsx";

const REASONS = {
  BASELINE_NEUTRAL: "Baseline week: the standard order is always served, so there is something to compare against.",
  WARMUP_ROUND_ROBIN: "Warm-up: every layout is tried in turn so the learner sees each one.",
  HIGHEST_UCB: "Chosen by the learner: best expected result, plus a bonus for layouts it is still unsure about.",
  FORCED_P0_ROLLBACK: "Back to the standard order: the last meal of this type tripped a safety check.",
  SINGLE_LAYOUT_ONLY: "Every policy gives the same order for this menu, so there is nothing to choose.",
  ALL_ALTERNATIVES_COOLING_DOWN: "Every nudged layout is paused after a safety check, so the standard order is served.",
};

export function reasonText(code) {
  return REASONS[code] || code;
}

export function rememberedName() {
  try {
    return localStorage.getItem("dining.name") || "";
  } catch {
    return "";
  }
}

export function rememberName(name) {
  try {
    localStorage.setItem("dining.name", name);
  } catch {
    /* ignore */
  }
}

/** The recommended layout and its alternatives; serve the recommendation or override it with a reason. */
export default function Candidates({ meal, semester, rec, onDeployed }) {
  const [picked, setPicked] = useState(rec.recommendedCandidateId);
  const [reason, setReason] = useState("");
  const [who, setWho] = useState(rememberedName());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const ordered = [...rec.candidates].sort((a, b) => (b.recommended ? 1 : 0) - (a.recommended ? 1 : 0) || a.d - b.d);
  const override = picked !== rec.recommendedCandidateId;

  async function serve(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      rememberName(who.trim());
      await api.deploy(meal.id, {
        deployedBy: who.trim(),
        candidateId: override ? picked : null,
        overrideReason: override ? reason.trim() : null,
      });
      onDeployed();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="step-body" onSubmit={serve}>
      <p className="reason">{reasonText(rec.reasonCode)}</p>
      <CarbonKey />
      <div className="candidates">
        {ordered.map((c) => (
          <label key={c.id} className={[
            "candidate",
            c.id === picked ? "picked" : "",
            c.excludedByCooldown ? "blocked" : "",
          ].join(" ")}>
            <input type="radio" name="candidate" value={c.id} checked={c.id === picked}
                   disabled={c.excludedByCooldown} onChange={() => setPicked(c.id)} />
            <div className="candidate-head">
              <strong>{c.recommended ? "Recommended" : "Alternative"}</strong>
              <span>{policyText(c.labels)}</span>
              {c.excludedByCooldown && <span className="tag warn">Paused after a safety check</span>}
            </div>
            <Counter dishOrder={c.dishOrder} items={meal.items} slots={semester.slots} compact={!c.recommended} />
            <dl className="candidate-stats">
              <div><dt>Nudge strength</dt><dd>{num(c.d, 3)}</dd></div>
              {c.predMean !== null && c.predMean !== undefined && (
                <div><dt>Expected score</dt><dd>{num(c.predMean, 3)}</dd></div>
              )}
              {c.bonus !== null && c.bonus !== undefined && (
                <div><dt>Uncertainty bonus</dt><dd>{num(c.bonus, 3)}</dd></div>
              )}
            </dl>
          </label>
        ))}
      </div>
      <div className="serve-row">
        <label>Served by<input value={who} required maxLength={100} onChange={(e) => setWho(e.target.value)} placeholder="Your name" /></label>
        {override && (
          <label className="grow">Why not the recommendation?
            <input value={reason} required onChange={(e) => setReason(e.target.value)} placeholder="For example: paneer ran short" />
          </label>
        )}
        <button className="btn primary" disabled={busy}>
          {busy ? "Savingâ€¦" : override ? "Serve this layout instead" : "Serve the recommendation"}
        </button>
      </div>
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </form>
  );
}