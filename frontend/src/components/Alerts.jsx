import { useEffect, useState } from "react";
import { api } from "../api.js";
import { errorText, num } from "../format.js";
import { rememberedName } from "./Candidates.jsx";

const RULES = {
  WASTE_SPIKE: "Plate waste jumped well above the baseline",
  WASTE_SUSTAINED: "Plate waste stayed high over the last three meals of this type",
  UPTAKE_DROP: "Students took far fewer counter dishes than usual",
  UPTAKE_SUSTAINED: "Students kept taking fewer counter dishes",
};

/** Safety checks that fired and nobody has looked at yet. */
export default function Alerts({ semester, version, onChange }) {
  const [events, setEvents] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.safetyEvents(semester.id).then(setEvents).catch(setError);
  }, [semester.id, version]);

  async function ack(id) {
    try {
      await api.acknowledge(id, rememberedName() || "manager");
      onChange();
    } catch (e) {
      setError(e);
    }
  }

  const shown = [...events].reverse().slice(0, 6);
  return (
    <section className="alerts" aria-labelledby="alerts-title">
      <h2 id="alerts-title">
        Safety checks <span className="count">{events.length ? `${events.length} open` : "all clear"}</span>
      </h2>
      {!events.length && <p className="hint">Nothing to review. When waste or uptake moves outside the baseline range, it shows up here.</p>}
      <ul>
        {shown.map((e) => (
          <li key={e.id} className={e.action === "ROLLBACK" ? "alert rollback" : "alert"}>
            <p className="alert-what">{RULES[e.rule] || e.rule}</p>
            <p className="alert-detail">
              Meal {e.mealIndex + 1}.{" "}
              {e.action === "ROLLBACK"
                ? `The next meal of this type goes back to the standard order, and ${e.cooldownLabels.join(", ")} ${e.cooldownLabels.length > 1 ? "are" : "is"} paused for three meals.`
                : "The standard order was on the counter, so nothing is paused."}
              {" "}Measured {num(e.observedValue, 3)} against a limit of {num(e.threshold, 3)}.
            </p>
            <button className="btn quiet small" onClick={() => ack(e.id)}>Mark as reviewed</button>
          </li>
        ))}
      </ul>
      {events.length > shown.length && <p className="hint">{events.length - shown.length} older checks not shown.</p>}
      {error && <pre className="banner error">{errorText(error)}</pre>}
    </section>
  );
}