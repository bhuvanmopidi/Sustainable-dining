package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import com.sustainabledining.meal.MenuItem;
import com.sustainabledining.semester.ServingSlot;
import java.util.*;

/** Builds the snake_case JSON payloads of contracts/schemas from database entities. */
public final class Payloads {
    private Payloads() {}

    public static Map<String, Object> meal(MealPeriod m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId().toString());
        out.put("meal_type", m.getMealType().name());
        out.put("date", m.getMealDate().toString());
        out.put("expected_attendance", m.getExpectedAttendance());
        out.put("enrolled", m.getSemester().getEnrolledStudents());
        return out;
    }

    public static List<Map<String, Object>> menuItems(MealPeriod m) {
        List<Map<String, Object>> out = new ArrayList<>();
        m.getMenuItems().stream().sorted(Comparator.comparingInt(MenuItem::getStandardSlot)).forEach(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", i.getId().toString());
            item.put("dish_id", i.getDish().getId().toString());
            item.put("category", i.getDish().getCategory());
            item.put("role", i.getRole().name());
            item.put("standard_slot", i.getStandardSlot());
            item.put("carbon_score", i.getCarbonScore().doubleValue());
            item.put("carbon_factor_snapshot", i.getCarbonFactorSnapshot().doubleValue());
            item.put("portion_mass_snapshot", i.getPortionMassSnapshot().doubleValue());
            out.add(item);
        });
        return out;
    }

    public static List<Map<String, Object>> slots(List<ServingSlot> slots) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ServingSlot s : slots) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("slot_index", s.getSlotIndex());
            slot.put("visibility", s.getVisibility().doubleValue());
            slot.put("eligible_slot", s.isEligibleSlot());
            out.add(slot);
        }
        return out;
    }

    public static Map<String, Object> observation(Observation o) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemOutcome it : o.getItems()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("menu_item_id", it.getMenuItem().getId().toString());
            item.put("portions_taken", it.getPortionsTaken());
            item.put("waste_g", it.getWasteG() == null ? null : it.getWasteG().doubleValue());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actual_attendance", o.getActualAttendance());
        out.put("aggregate_waste_g", o.getAggregateWasteG().doubleValue());
        out.put("item_outcomes", items);
        return out;
    }

    public static double dbl(Object v) {
        return ((Number) v).doubleValue();
    }

    public static Double dblOrNull(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) out.add(String.valueOf(o));
        return out;
    }

    public static List<Double> doubles(Object v) {
        List<Double> out = new ArrayList<>();
        for (Object o : (List<?>) v) out.add(((Number) o).doubleValue());
        return out;
    }
}