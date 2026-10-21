import { carbonColor, num } from "../format.js";

/**
 * The serving counter seen from the queue: slots left to right in the order students reach them, each tray
 * drawn as large as that slot is visible, coloured by the dish's carbon footprint. This is what a layout
 * actually changes, so it is the main picture of the dashboard.
 */
export default function Counter({ dishOrder, items, slots, compact = false }) {
  const byName = new Map((items || []).map((i) => [i.dishName, i]));
  const eligibleSlots = slots.filter((s) => s.eligibleSlot).sort((a, b) => a.slotIndex - b.slotIndex);
  const fixedSlots = slots.filter((s) => !s.eligibleSlot).sort((a, b) => a.slotIndex - b.slotIndex);
  const fixedItems = (items || []).filter((i) => i.role === "FIXED");

  const trays = [
    ...eligibleSlots.map((s, k) => ({ slot: s, item: byName.get(dishOrder?.[k]) || null, name: dishOrder?.[k] })),
    ...fixedSlots.map((s) => {
      const item = fixedItems.find((i) => i.standardSlot === s.slotIndex) || null;
      return { slot: s, item, name: item?.dishName };
    }),
  ];

  return (
    <div className={compact ? "counter compact" : "counter"} role="list" aria-label="Serving counter, first slot on the left">
      {trays.map(({ slot, item, name }) => {
        const vis = Number(slot.visibility);
        const filled = Boolean(name);
        return (
          <div key={slot.slotIndex} role="listitem" className={slot.eligibleSlot ? "tray-cell" : "tray-cell fixed"}>
            <div className="tray-well">
              <div
                className={filled ? "tray" : "tray empty"}
                style={{
                  "--fill": `${30 + 70 * vis}%`,
                  background: filled ? carbonColor(item ? Number(item.carbonScore) : 0.5) : undefined,
                }}
                title={item ? `${item.dishName}: ${num(item.carbonFactorSnapshot, 2)} kg CO2e per serving` : "empty slot"}
              >
                {filled && <span className="tray-name">{prettyDish(name)}</span>}
              </div>
            </div>
            {!compact && (
              <div className="tray-meta">
                <span>{slot.eligibleSlot ? `Slot ${slot.slotIndex}` : "Fixed"}</span>
                <span>{Math.round(vis * 100)}% notice</span>
                {item && <span>{num(item.carbonFactorSnapshot, 2)} kg</span>}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function prettyDish(name) {
  if (!name) return "";
  return name.replaceAll("_", " ").replace(/^./, (c) => c.toUpperCase());
}

export function CarbonKey() {
  return (
    <div className="carbon-key" aria-hidden="true">
      <span>Lower carbon</span>
      <span className="carbon-ramp" />
      <span>Higher carbon</span>
      <span className="key-gap">Taller tray = noticed by more students</span>
    </div>
  );
}