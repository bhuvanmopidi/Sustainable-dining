package com.sustainabledining.imports;

import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.common.ApiError;
import com.sustainabledining.common.ApiException;
import com.sustainabledining.imports.CsvTable.Row;
import com.sustainabledining.imports.ImportDtos.ImportResult;
import com.sustainabledining.imports.ImportDtos.ImportedMeal;
import com.sustainabledining.meal.*;
import com.sustainabledining.meal.MealDtos.PlanItem;
import com.sustainabledining.meal.MealDtos.PlanRequest;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.semester.SemesterMode;
import com.sustainabledining.semester.SemesterService;
import com.sustainabledining.workflow.WorkflowDtos.DeployRequest;
import com.sustainabledining.workflow.WorkflowDtos.EvaluationView;
import com.sustainabledining.workflow.WorkflowDtos.ObservationRequest;
import com.sustainabledining.workflow.WorkflowDtos.ObservedItem;
import com.sustainabledining.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk entry for LIVE semesters from CSV files. One row per (meal, dish). A file is all-or-nothing: it is fully
 * checked first, and if anything fails while applying it, the whole transaction is rolled back.
 *
 * Menus:        date, meal_type, dish, slot, expected_attendance [, role]
 * Observations: date, meal_type, dish, portions_taken, attendance, total_waste_g [, waste_g]
 *
 * Observation imports run each meal through the normal workflow (observe, then evaluate). Baseline meals that are
 * still PLANNED are recommended and deployed first, because during the baseline the layout is always the neutral
 * one; any later meal must already be DEPLOYED, since only the manager knows which layout was really served.
 */
@Service
@Transactional
public class ImportService {
    static final int MAX_ROWS = 20_000;

    private final SemesterService semesters;
    private final MealPeriodRepository meals;
    private final MealService mealService;
    private final DishRepository dishes;
    private final WorkflowService workflow;

    public ImportService(SemesterService semesters, MealPeriodRepository meals, MealService mealService,
                         DishRepository dishes, WorkflowService workflow) {
        this.semesters = semesters;
        this.meals = meals;
        this.mealService = mealService;
        this.dishes = dishes;
        this.workflow = workflow;
    }

    // ------------------------------------------------------------------ menus
    private static final class MenuDraft {
        final MealPeriod meal;
        final int expected;
        final int firstLine;
        final List<PlanItem> items = new ArrayList<>();

        MenuDraft(MealPeriod meal, int expected, int firstLine) {
            this.meal = meal;
            this.expected = expected;
            this.firstLine = firstLine;
        }
    }

    public ImportResult importMenus(UUID semesterId, String csv, boolean dryRun) {
        Semester semester = liveSemester(semesterId);
        CsvTable table = CsvTable.parse(csv, MAX_ROWS);
        table.requireColumns("date", "meal_type", "dish", "slot", "expected_attendance");
        Map<String, MealPeriod> mealByKey = mealIndex(semester);
        Map<String, Dish> dishByName = dishIndex();
        List<ApiError.Detail> errors = new ArrayList<>();
        Map<Integer, MenuDraft> drafts = new TreeMap<>();

        for (Row row : table.rows) {
            String where = "line " + row.line();
            MealPeriod meal = resolveMeal(row, mealByKey, errors);
            Dish dish = resolveDish(row, dishByName, errors);
            Integer slot = parseInt(row, "slot", 1, errors);
            Integer expected = parseInt(row, "expected_attendance", 0, errors);
            ItemRole role = parseRole(row, dish, errors);
            if (meal == null || dish == null || slot == null || expected == null || role == null) {
                continue;
            }
            if (meal.getState() != MealState.PLANNED) {
                errors.add(new ApiError.Detail(where, label(meal) + " is " + meal.getState()
                        + "; menus can only be imported for PLANNED meals"));
                continue;
            }
            MenuDraft draft = drafts.computeIfAbsent(meal.getMealIndex(), k -> new MenuDraft(meal, expected, row.line()));
            if (draft.expected != expected) {
                errors.add(new ApiError.Detail(where + ": expected_attendance", "is " + expected + " but line "
                        + draft.firstLine + " gives " + draft.expected + " for the same meal"));
            }
            draft.items.add(new PlanItem(dish.getId(), role, slot));
        }
        if (table.rows.isEmpty()) {
            errors.add(new ApiError.Detail("file", "no data rows after the header"));
        }
        throwIfAny(errors);

        List<ImportedMeal> out = new ArrayList<>();
        for (MenuDraft d : drafts.values()) {
            if (!dryRun) {
                try {
                    mealService.plan(d.meal.getId(), new PlanRequest(d.expected, d.items));
                } catch (ApiException e) {
                    errors.add(new ApiError.Detail(label(d.meal), e.getMessage()));
                    for (ApiError.Detail detail : e.details()) {
                        errors.add(new ApiError.Detail(label(d.meal) + ": " + detail.field(), detail.message()));
                    }
                    continue;
                }
            }
            out.add(new ImportedMeal(d.meal.getMealIndex(), d.meal.getMealDate(), d.meal.getMealType(),
                    dryRun ? "WOULD_PLAN" : "PLANNED", d.meal.getState(), null, 0));
        }
        throwIfAny(errors);
        return new ImportResult("MENUS", dryRun, table.rows.size(), out.size(), out);
    }

    // ------------------------------------------------------------------ observations
    private static final class ObservationDraft {
        final MealPeriod meal;
        final int attendance;
        final BigDecimal totalWaste;
        final int firstLine;
        final Map<String, ObservedRow> byDish = new LinkedHashMap<>();

        ObservationDraft(MealPeriod meal, int attendance, BigDecimal totalWaste, int firstLine) {
            this.meal = meal;
            this.attendance = attendance;
            this.totalWaste = totalWaste;
            this.firstLine = firstLine;
        }
    }

    private record ObservedRow(int line, String dishName, int portions, BigDecimal wasteG) {}

    public ImportResult importObservations(UUID semesterId, String csv, String enteredBy, boolean dryRun) {
        Semester semester = liveSemester(semesterId);
        String by = enteredBy == null || enteredBy.isBlank() ? "csv-import" : enteredBy.trim();
        CsvTable table = CsvTable.parse(csv, MAX_ROWS);
        table.requireColumns("date", "meal_type", "dish", "portions_taken", "attendance", "total_waste_g");
        Map<String, MealPeriod> mealByKey = mealIndex(semester);
        List<ApiError.Detail> errors = new ArrayList<>();
        Map<Integer, ObservationDraft> drafts = new TreeMap<>();

        for (Row row : table.rows) {
            String where = "line " + row.line();
            MealPeriod meal = resolveMeal(row, mealByKey, errors);
            String dishName = row.get("dish");
            if (dishName.isEmpty()) {
                errors.add(new ApiError.Detail(where + ": dish", "is empty"));
            }
            Integer portions = parseInt(row, "portions_taken", 0, errors);
            Integer attendance = parseInt(row, "attendance", 0, errors);
            BigDecimal totalWaste = parseDecimal(row, "total_waste_g", true, errors);
            BigDecimal wasteG = parseDecimal(row, "waste_g", false, errors);
            if (meal == null || dishName.isEmpty() || portions == null || attendance == null || totalWaste == null
                    || (wasteG == null && !row.get("waste_g").isEmpty())) {
                continue;
            }
            ObservationDraft draft = drafts.computeIfAbsent(meal.getMealIndex(),
                    k -> new ObservationDraft(meal, attendance, totalWaste, row.line()));
            if (draft.attendance != attendance) {
                errors.add(new ApiError.Detail(where + ": attendance", "is " + attendance + " but line "
                        + draft.firstLine + " gives " + draft.attendance + " for the same meal"));
            }
            if (draft.totalWaste.compareTo(totalWaste) != 0) {
                errors.add(new ApiError.Detail(where + ": total_waste_g", "is " + totalWaste.toPlainString()
                        + " but line " + draft.firstLine + " gives " + draft.totalWaste.toPlainString()
                        + " for the same meal"));
            }
            String key = dishName.toLowerCase(Locale.ROOT);
            if (draft.byDish.containsKey(key)) {
                errors.add(new ApiError.Detail(where + ": dish", "'" + dishName + "' is listed twice for "
                        + label(meal) + " (first on line " + draft.byDish.get(key).line() + ")"));
                continue;
            }
            draft.byDish.put(key, new ObservedRow(row.line(), dishName, portions, wasteG));
        }
        if (table.rows.isEmpty()) {
            errors.add(new ApiError.Detail("file", "no data rows after the header"));
        }

        // Checks that need the meal itself: its state and its menu.
        for (ObservationDraft d : drafts.values()) {
            MealPeriod meal = d.meal;
            String where = "line " + d.firstLine + " (" + label(meal) + ")";
            if (meal.getMenuItems().isEmpty()) {
                errors.add(new ApiError.Detail(where, "has no menu yet; import or plan the menu first"));
                continue;
            }
            MealState state = meal.getState();
            boolean autoAdvance = state == MealState.PLANNED && meal.getPhase() == Phase.BASELINE;
            if (state == MealState.EVALUATED) {
                errors.add(new ApiError.Detail(where, "already has an observation; to change it use "
                        + "POST /api/meals/" + meal.getId() + "/observation/correction"));
                continue;
            }
            if (state != MealState.DEPLOYED && !autoAdvance) {
                errors.add(new ApiError.Detail(where, "is " + state + "; outside the baseline a meal must be "
                        + "recommended and deployed before its observation can be imported"));
                continue;
            }
            Set<String> onMenu = new HashSet<>();
            for (MenuItem item : meal.getMenuItems()) {
                String name = item.getDish().getName().toLowerCase(Locale.ROOT);
                onMenu.add(name);
                if (!d.byDish.containsKey(name)) {
                    errors.add(new ApiError.Detail(where, "no row for '" + item.getDish().getName()
                            + "', which is on the menu (every menu dish needs a row, use 0 if none was taken)"));
                }
            }
            for (ObservedRow r : d.byDish.values()) {
                if (!onMenu.contains(r.dishName().toLowerCase(Locale.ROOT))) {
                    errors.add(new ApiError.Detail("line " + r.line(), "'" + r.dishName() + "' is not on the menu of "
                            + label(meal)));
                }
            }
        }
        throwIfAny(errors);

        List<ImportedMeal> out = new ArrayList<>();
        for (ObservationDraft d : drafts.values()) {
            MealPeriod meal = d.meal;
            boolean advanced = meal.getState() == MealState.PLANNED;
            if (dryRun) {
                out.add(new ImportedMeal(meal.getMealIndex(), meal.getMealDate(), meal.getMealType(),
                        advanced ? "WOULD_DEPLOY_NEUTRAL_AND_OBSERVE" : "WOULD_OBSERVE", meal.getState(), null, 0));
                continue;
            }
            try {
                if (advanced) {
                    workflow.recommend(meal.getId());
                    workflow.deploy(meal.getId(), new DeployRequest(null, by, null));
                }
                List<ObservedItem> items = new ArrayList<>();
                for (MenuItem item : meal.getMenuItems()) {
                    ObservedRow r = d.byDish.get(item.getDish().getName().toLowerCase(Locale.ROOT));
                    items.add(new ObservedItem(item.getId(), r.portions(), r.wasteG()));
                }
                workflow.observe(meal.getId(), new ObservationRequest(d.attendance, d.totalWaste, items, by), "CSV");
                EvaluationView v = workflow.evaluate(meal.getId());
                out.add(new ImportedMeal(meal.getMealIndex(), meal.getMealDate(), meal.getMealType(),
                        advanced ? "DEPLOYED_NEUTRAL_AND_OBSERVED" : "OBSERVED", v.state(), v.reward(),
                        v.safetyEvents().size()));
            } catch (ApiException e) {
                throw new ApiException(e.status(), e.code(), label(meal) + ": " + e.getMessage()
                        + " (nothing from this file was saved)", e.details());
            }
        }
        return new ImportResult("OBSERVATIONS", dryRun, table.rows.size(), out.size(), out);
    }

    // ------------------------------------------------------------------ helpers
    private Semester liveSemester(UUID semesterId) {
        Semester semester = semesters.load(semesterId);
        if (semester.getMode() != SemesterMode.LIVE) {
            throw ApiException.conflict("NOT_LIVE",
                    "CSV imports are for LIVE semesters; demo semesters get their data from the simulator");
        }
        return semester;
    }

    private Map<String, MealPeriod> mealIndex(Semester semester) {
        Map<String, MealPeriod> out = new HashMap<>();
        for (MealPeriod m : meals.findBySemesterIdOrderByMealIndexAsc(semester.getId())) {
            out.put(m.getMealDate() + "|" + m.getMealType(), m);
        }
        return out;
    }

    private Map<String, Dish> dishIndex() {
        Map<String, Dish> out = new HashMap<>();
        for (Dish d : dishes.findAll()) {
            out.put(d.getName().toLowerCase(Locale.ROOT), d);
        }
        return out;
    }

    private static MealPeriod resolveMeal(Row row, Map<String, MealPeriod> mealByKey, List<ApiError.Detail> errors) {
        String where = "line " + row.line();
        LocalDate date;
        MealType type;
        try {
            date = LocalDate.parse(row.get("date"));
        } catch (DateTimeParseException e) {
            errors.add(new ApiError.Detail(where + ": date", "'" + row.get("date") + "' is not a date like 2026-07-06"));
            return null;
        }
        try {
            type = MealType.valueOf(row.get("meal_type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.Detail(where + ": meal_type", "'" + row.get("meal_type")
                    + "' must be BREAKFAST, LUNCH or DINNER"));
            return null;
        }
        MealPeriod meal = mealByKey.get(date + "|" + type);
        if (meal == null) {
            errors.add(new ApiError.Detail(where, "this semester has no meal on " + date + " " + type));
        }
        return meal;
    }

    private static Dish resolveDish(Row row, Map<String, Dish> dishByName, List<ApiError.Detail> errors) {
        String name = row.get("dish");
        Dish dish = dishByName.get(name.toLowerCase(Locale.ROOT));
        if (dish == null) {
            errors.add(new ApiError.Detail("line " + row.line() + ": dish", name.isEmpty() ? "is empty"
                    : "unknown dish '" + name + "' (add it to the dish catalogue first)"));
        }
        return dish;
    }

    private static ItemRole parseRole(Row row, Dish dish, List<ApiError.Detail> errors) {
        String raw = row.get("role");
        if (raw.isEmpty()) {
            return dish == null ? null : dish.isEligibleByDefault() ? ItemRole.ELIGIBLE : ItemRole.FIXED;
        }
        try {
            return ItemRole.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new ApiError.Detail("line " + row.line() + ": role", "'" + raw + "' must be ELIGIBLE or FIXED"));
            return null;
        }
    }

    private static Integer parseInt(Row row, String column, int min, List<ApiError.Detail> errors) {
        String raw = row.get(column);
        try {
            int v = Integer.parseInt(raw);
            if (v < min) {
                errors.add(new ApiError.Detail("line " + row.line() + ": " + column, "must be at least " + min));
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(new ApiError.Detail("line " + row.line() + ": " + column,
                    raw.isEmpty() ? "is empty" : "'" + raw + "' is not a whole number"));
            return null;
        }
    }

    /** Non-negative decimal. An optional column that is empty gives null without an error. */
    private static BigDecimal parseDecimal(Row row, String column, boolean required, List<ApiError.Detail> errors) {
        String raw = row.get(column);
        if (raw.isEmpty()) {
            if (required) {
                errors.add(new ApiError.Detail("line " + row.line() + ": " + column, "is empty"));
            }
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw);
            if (v.signum() < 0) {
                errors.add(new ApiError.Detail("line " + row.line() + ": " + column, "cannot be negative"));
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(new ApiError.Detail("line " + row.line() + ": " + column, "'" + raw + "' is not a number"));
            return null;
        }
    }

    private static String label(MealPeriod m) {
        return m.getMealDate() + " " + m.getMealType() + " (meal " + m.getMealIndex() + ")";
    }

    private static void throwIfAny(List<ApiError.Detail> errors) {
        if (!errors.isEmpty()) {
            List<ApiError.Detail> shown = errors.size() > 200 ? errors.subList(0, 200) : errors;
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IMPORT_INVALID",
                    "the file has " + errors.size() + " problem(s); nothing was saved", new ArrayList<>(shown));
        }
    }
}