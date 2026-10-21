import { useMemo, useState } from "react";
import { MEAL_NAMES, formatDate, mean, num, policyText, signed } from "../format.js";

const W = 1000;          // drawing units; the SVG scales to the panel width
const LEFT = 64;
const RIGHT = 12;
const PER_WEEK = 21;

const POLICY_COLORS = { P0: "var(--p0)", P1: "var(--p1)", P2: "var(--p2)", P3: "var(--p3)" };

function rolling(values, window) {
  const out = [];
  for (let i = 0; i < values.length; i++) {
    const slice = values.slice(Math.max(0, i - window + 1), i + 1).filter((v) => v !== null && v !== undefined);
    out.push(slice.length >= Math.min(window, 3) ? slice.reduce((a, b) => a + b, 0) / slice.length : null);
  }
  return out;
}

function extent(values, pad = 0.08) {
  const xs = values.filter((v) => v !== null && v !== undefined && Number.isFinite(v));
  if (!xs.length) return [0, 1];
  let lo = Math.min(...xs);
  let hi = Math.max(...xs);
  if (lo === hi) { lo -= 1; hi += 1; }
  const p = (hi - lo) * pad;
  return [lo - p, hi + p];
}

/** The semester at a glance: one shared meal axis, four tracks. Hover any track to read one meal. */
export default function Charts({ timeline }) {
  const [hover, setHover] = useState(null);
  const n = timeline.length;
  const x = (i) => LEFT + (i + 0.5) * ((W - LEFT - RIGHT) / Math.max(n, 1));
  const step = (W - LEFT - RIGHT) / Math.max(n, 1);
  const baselineEnd = timeline.findIndex((r) => r.phase !== "BASELINE");
  const warmupEnd = timeline.findIndex((r) => r.phase === "ADAPTIVE");
  const scored = timeline.some((r) => r.state === "EVALUATED");

  const series = useMemo(() => {
    const carbon = timeline.map((r) => r.carbonPerAttendee);
    const waste = timeline.map((r) => r.wasteGPerAttendee);
    const reward = timeline.map((r) => r.reward);
    const base = timeline.filter((r) => r.phase === "BASELINE");
    return {
      carbon, waste, reward,
      carbonAvg: rolling(carbon, PER_WEEK), wasteAvg: rolling(waste, PER_WEEK), rewardAvg: rolling(reward, PER_WEEK),
      carbonBase: mean(base.map((r) => r.carbonPerAttendee)),
      wasteBase: mean(base.map((r) => r.wasteGPerAttendee)),
    };
  }, [timeline]);

  if (!n) return null;

  function onMove(e) {
    const svg = e.currentTarget;
    const box = svg.getBoundingClientRect();
    const ux = ((e.clientX - box.left) / box.width) * W;
    const i = Math.floor((ux - LEFT) / step);
    setHover(i >= 0 && i < n ? i : null);
  }

  const h = hover !== null ? timeline[hover] : null;
  const bands = (height) => (
    <g aria-hidden="true">
      {baselineEnd > 0 && <rect x={LEFT} y={0} width={baselineEnd * step} height={height} className="band-baseline" />}
      {warmupEnd > baselineEnd && baselineEnd >= 0 &&
        <rect x={LEFT + baselineEnd * step} y={0} width={(warmupEnd - baselineEnd) * step} height={height} className="band-warmup" />}
      {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={0} y2={height} className="hover-line" />}
    </g>
  );

  return (
    <section className="charts" aria-labelledby="charts-title">
      <div className="charts-head">
        <h2 id="charts-title">The semester, meal by meal</h2>
        <p className="readout" aria-live="polite">
          {h ? (
            <>
              <strong>{MEAL_NAMES[h.mealType]}, {formatDate(h.date)}</strong>{" "}
              {h.state === "EVALUATED"
                ? <>served {policyText(h.labels)}; carbon {num(h.carbonPerAttendee, 3)} kg, waste {num(h.wasteGPerAttendee, 1)} g
                    {h.reward !== null && <>, score {signed(h.reward)}</>}
                    {h.rollback && <>, rolled back</>}</>
                : <>not served yet</>}
            </>
          ) : scored ? "Point at a meal to read it. Lines are one-week averages; the shaded weeks are the baseline and the warm-up." : "No meals scored yet."}
        </p>
      </div>
      <Track title="Nudge on the counter" height={70} onMove={onMove} onLeave={() => setHover(null)} bands={bands}
             axis={["strong", "none"]}>
        {timeline.map((r, i) => r.d !== null && r.d !== undefined && (
          <rect key={r.mealId} x={x(i) - step * 0.4} width={Math.max(step * 0.8, 0.8)}
                y={70 - 6 - Math.min(1, r.d / 0.6) * 58} height={Math.max(1, Math.min(1, r.d / 0.6) * 58)}
                fill={POLICY_COLORS[[...r.labels].sort().at(-1)] || "var(--p0)"} />
        ))}
        {timeline.map((r, i) => r.rollback && (
          <circle key={"rb" + r.mealId} cx={x(i)} cy={8} r={3.2} className="rollback-dot" />
        ))}
      </Track>
      <Line title="Carbon per student (kg)" values={series.carbon} avg={series.carbonAvg} reference={series.carbonBase}
            x={x} n={n} onMove={onMove} onLeave={() => setHover(null)} bands={bands} digits={2} />
      <Line title="Plate waste per student (g)" values={series.waste} avg={series.wasteAvg} reference={series.wasteBase}
            x={x} n={n} onMove={onMove} onLeave={() => setHover(null)} bands={bands} digits={0} />
      <Line title="Score (above 0 beats the baseline)" values={series.reward} avg={series.rewardAvg} reference={0}
            x={x} n={n} onMove={onMove} onLeave={() => setHover(null)} bands={bands} digits={2} />
      <svg viewBox={`0 0 ${W} 22`} className="week-axis" aria-hidden="true">
        {Array.from({ length: Math.ceil(n / PER_WEEK) }, (_, k) => (
          <text key={k} x={LEFT + (k + 0.5) * PER_WEEK * step} y={15} textAnchor="middle">Week {k + 1}</text>
        ))}
      </svg>
      <div className="legend">
        <span><i style={{ background: "var(--p0)" }} />P0 standard order</span>
        <span><i style={{ background: "var(--p1)" }} />P1 light nudge</span>
        <span><i style={{ background: "var(--p2)" }} />P2 medium nudge</span>
        <span><i style={{ background: "var(--p3)" }} />P3 strong nudge</span>
        <span><i className="dot" />rollback</span>
      </div>
    </section>
  );
}

function Track({ title, height, children, onMove, onLeave, bands, axis }) {
  return (
    <figure className="track">
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${W} ${height}`} onMouseMove={onMove} onMouseLeave={onLeave} role="img" aria-label={title}>
        {bands(height)}
        {axis && (
          <g className="axis-text">
            <text x={LEFT - 8} y={14} textAnchor="end">{axis[0]}</text>
            <text x={LEFT - 8} y={height - 6} textAnchor="end">{axis[1]}</text>
          </g>
        )}
        {children}
      </svg>
    </figure>
  );
}

function Line({ title, values, avg, reference, x, n, onMove, onLeave, bands, digits }) {
  const height = 110;
  const top = 8;
  const bottom = height - 8;
  const [lo, hi] = extent([...values, reference]);
  const y = (v) => bottom - ((v - lo) / (hi - lo)) * (bottom - top);
  let path = "";
  let pen = false;
  avg.forEach((v, i) => {
    if (v === null || v === undefined) { pen = false; return; }
    path += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
    pen = true;
  });
  const has = values.some((v) => v !== null && v !== undefined);
  return (
    <figure className="track">
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${W} ${height}`} onMouseMove={onMove} onMouseLeave={onLeave} role="img" aria-label={title}>
        {bands(height)}
        <g className="axis-text">
          <text x={LEFT - 8} y={top + 8} textAnchor="end">{num(hi, digits)}</text>
          <text x={LEFT - 8} y={bottom} textAnchor="end">{num(lo, digits)}</text>
        </g>
        {reference !== null && reference !== undefined && has && (
          <line x1={LEFT} x2={W - RIGHT} y1={y(reference)} y2={y(reference)} className="reference-line" />
        )}
        {values.map((v, i) => v !== null && v !== undefined && (
          <circle key={i} cx={x(i)} cy={y(v)} r={1.7} className="point" />
        ))}
        <path d={path} className="avg-line" />
        {!has && <text x={W / 2} y={height / 2} textAnchor="middle" className="axis-text">No data yet</text>}
      </svg>
    </figure>
  );
}