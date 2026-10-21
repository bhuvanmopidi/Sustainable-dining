// Small display helpers shared by the components.

export const MEAL_NAMES = { BREAKFAST: "Breakfast", LUNCH: "Lunch", DINNER: "Dinner" };
export const PHASE_NAMES = { BASELINE: "Baseline", WARMUP: "Warm-up", ADAPTIVE: "Adaptive" };

// What each policy does to the counter, in the manager's words.
export const POLICY_NAMES = {
  P0: "standard order",
  P1: "light nudge",
  P2: "medium nudge",
  P3: "strong nudge",
};

export function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
}

export function num(v, digits = 2) {
  return v === null || v === undefined || Number.isNaN(v) ? "â€“" : Number(v).toFixed(digits);
}

export function signed(v, digits = 3) {
  if (v === null || v === undefined) return "â€“";
  return (v >= 0 ? "+" : "âˆ’") + Math.abs(v).toFixed(digits);
}

export function pct(v, digits = 1) {
  if (v === null || v === undefined || !Number.isFinite(v)) return "â€“";
  return (v >= 0 ? "+" : "âˆ’") + Math.abs(v * 100).toFixed(digits) + "%";
}

export function mean(values) {
  const xs = values.filter((v) => v !== null && v !== undefined && Number.isFinite(v));
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
}

export function strongestPolicy(labels) {
  if (!labels || labels.length === 0) return null;
  return [...labels].sort().at(-1);
}

export function policyText(labels) {
  if (!labels || labels.length === 0) return "â€“";
  const names = labels.map((l) => POLICY_NAMES[l] || l);
  return labels.length > 1 ? `${labels.join(" = ")} (same order)` : `${labels[0]}, ${names[0]}`;
}

// Carbon score 0 (lowest in the catalogue) .. 1 (highest) -> leaf green .. masala brown.
export function carbonColor(score) {
  // Square root spreads the many low-carbon dishes apart from the few very high ones (mutton is the 1.0).
  const s = Math.sqrt(Math.max(0, Math.min(1, score ?? 0.25)));
  const a = [47, 125, 79];
  const b = [138, 90, 58];
  const c = a.map((x, i) => Math.round(x + (b[i] - x) * s));
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`;
}

export function errorText(e) {
  if (!e) return "";
  const extra = (e.details || []).slice(0, 6).map((d) => `${d.field}: ${d.message}`);
  return [e.message, ...extra].join("\n");
}