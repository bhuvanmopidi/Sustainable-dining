package com.sustainabledining.meal;

import com.sustainabledining.catalog.CarbonScorer;
import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.common.ApiError;
import com.sustainabledining.common.ApiException;
import com.sustainabledining.meal.MealDtos.*;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.semester.SemesterRepository;
import com.sustainabledining.semester.ServingSlot;
import com.sustainabledining.semester.ServingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MealService {
    private final MealPeriodRepository meals;
    private final SemesterRepository semesters;
    private final ServingSlotRepository slots;
    private final DishRepository dishes;
    private final CarbonScorer scorer;

    public MealService(MealPeriodRepository meals, SemesterRepository semesters, ServingSlotRepository slots,
                       DishRepository dishes, CarbonScorer scorer) {
        this.meals = meals;
        this.semesters = semesters;
        this.slots = slots;
        this.dishes = dishes;
        this.scorer = scorer;
    }

    @Transactional(readOnly = true)
    public List<MealSummary> list(UUID semesterId, MealState state) {
        if (!semesters.existsById(semesterId)) {
            throw ApiException.notFound("semester", semesterId);
        }
        List<MealPeriod> list = state == null
                ? meals.findBySemesterIdOrderByMealIndexAsc(semesterId)
                : meals.findBySemesterIdAndStateOrderByMealIndexAsc(semesterId, state);
        return list.stream().map(MealService::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public MealDetail get(UUID mealId) {
        return toDetail(load(mealId));
    }

    @Transactional(readOnly = true)
    public MealDetail next(UUID semesterId) {
        if (!semesters.existsById(semesterId)) {
            throw ApiException.notFound("semester", semesterId);
        }
        return meals.findFirstBySemesterIdAndStateNotOrderByMealIndexAsc(semesterId, MealState.EVALUATED)
                .map(MealService::toDetail)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SEMESTER_COMPLETE",
                        "every meal in this semester has been evaluated", List.of()));
    }

    @Transactional(readOnly = true)
    public MealPeriod load(UUID mealId) {
        return meals.findById(mealId).orElseThrow(() -> ApiException.notFound("meal", mealId));
    }

    /** Meals run strictly in order, so the learner always sees a complete history. */
    @Transactional(readOnly = true)
    public void assertEarlierMealsEvaluated(MealPeriod meal) {
        if (meals.existsBySemesterIdAndMealIndexLessThanAndStateNot(
                meal.getSemester().getId(), meal.getMealIndex(), MealState.EVALUATED)) {
            throw ApiException.conflict("OUT_OF_ORDER",
                    "every earlier meal must be EVALUATED before meal " + meal.getMealIndex() + " can proceed");
        }
    }

    /** Saves (or replaces) the menu and expected attendance. Only allowed while the meal is PLANNED. */
    public MealDetail plan(UUID mealId, PlanRequest request) {
        MealPeriod meal = loadPlannable(mealId);
        Semester semester = meal.getSemester();
        List<ApiError.Detail> errors = new ArrayList<>();

        if (request.expectedAttendance() > semester.getEnrolledStudents()) {
            errors.add(new ApiError.Detail("expectedAttendance",
                    "cannot exceed enrolled students (" + semester.getEnrolledStudents() + ")"));
        }
        Map<Integer, ServingSlot> slotByIndex = slots.findBySemesterIdOrderBySlotIndexAsc(semester.getId()).stream()
                .collect(Collectors.toMap(ServingSlot::getSlotIndex, s -> s));
        Set<Integer> usedSlots = new HashSet<>();
        Set<UUID> usedDishes = new HashSet<>();
        Map<UUID, Dish> dishById = new HashMap<>();

        for (int i = 0; i < request.items().size(); i++) {
            PlanItem item = request.items().get(i);
            String field = "items[" + i + "]";
            ServingSlot slot = slotByIndex.get(item.standardSlot());
            if (slot == null) {
                errors.add(new ApiError.Detail(field + ".standardSlot", "slot " + item.standardSlot() + " does not exist"));
            } else if (slot.isEligibleSlot() != (item.role() == ItemRole.ELIGIBLE)) {
                errors.add(new ApiError.Detail(field + ".role", item.role() == ItemRole.ELIGIBLE
                        ? "eligible items must be in an eligible slot" : "fixed items must be in a fixed slot"));
            }
            if (!usedSlots.add(item.standardSlot())) {
                errors.add(new ApiError.Detail(field + ".standardSlot", "slot " + item.standardSlot() + " is used twice"));
            }
            if (!usedDishes.add(item.dishId())) {
                errors.add(new ApiError.Detail(field + ".dishId", "dish is listed twice"));
            }
            Optional<Dish> dish = dishes.findById(item.dishId());
            if (dish.isEmpty()) {
                errors.add(new ApiError.Detail(field + ".dishId", "unknown dish"));
            } else if (!dish.get().isActive()) {
                errors.add(new ApiError.Detail(field + ".dishId", "dish is inactive"));
            } else {
                dishById.put(item.dishId(), dish.get());
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MENU", "the menu is not valid", errors);
        }

        clearMenu(meal);
        CarbonScorer.Range range = scorer.currentRange();
        for (PlanItem item : request.items()) {
            Dish dish = dishById.get(item.dishId());
            meal.getMenuItems().add(new MenuItem(meal, dish, item.role(), item.standardSlot(),
                    dish.getCarbonKgPerServing(), dish.getPortionMassG(), range.score(dish.getCarbonKgPerServing())));
        }
        meal.setExpectedAttendance(request.expectedAttendance());
        meals.saveAndFlush(meal);
        return toDetail(meal);
    }

    /**
     * Demo mode: copy the simulator's menu exactly (its carbon scores, snapshots and expected attendance),
     * creating catalogue dishes by name if they do not exist yet.
     */
    @SuppressWarnings("unchecked")
    public MealDetail planFromSimulator(UUID mealId, Map<String, Object> spec) {
        MealPeriod meal = loadPlannable(mealId);
        Map<String, Object> simMeal = (Map<String, Object>) spec.get("meal");
        Map<String, Object> names = (Map<String, Object>) spec.get("dish_names");
        clearMenu(meal);
        for (Object o : (List<Object>) spec.get("menu_items")) {
            Map<String, Object> it = (Map<String, Object>) o;
            String name = String.valueOf(names.get(String.valueOf(it.get("dish_id"))));
            BigDecimal carbon = new BigDecimal(String.valueOf(it.get("carbon_factor_snapshot")));
            BigDecimal mass = new BigDecimal(String.valueOf(it.get("portion_mass_snapshot")));
            BigDecimal score = new BigDecimal(String.valueOf(it.get("carbon_score"))).setScale(6, RoundingMode.HALF_UP);
            ItemRole role = ItemRole.valueOf(String.valueOf(it.get("role")));
            Dish dish = dishes.findByNameIgnoreCase(name).orElseGet(() -> dishes.save(
                    new Dish(name, String.valueOf(it.get("category")), carbon, mass, role == ItemRole.ELIGIBLE)));
            meal.getMenuItems().add(new MenuItem(meal, dish, role, ((Number) it.get("standard_slot")).intValue(),
                    carbon, mass, score));
        }
        meal.setExpectedAttendance(((Number) simMeal.get("expected_attendance")).intValue());
        meals.saveAndFlush(meal);
        return toDetail(meal);
    }

    private MealPeriod loadPlannable(UUID mealId) {
        MealPeriod meal = load(mealId);
        if (meal.getState() != MealState.PLANNED) {
            throw ApiException.conflict("NOT_PLANNABLE",
                    "the menu can only be changed while the meal is PLANNED (it is " + meal.getState() + ")");
        }
        return meal;
    }

    /** Delete old rows BEFORE inserting new ones, or the unique (meal_id, standard_slot) constraint fires. */
    private void clearMenu(MealPeriod meal) {
        meal.getMenuItems().clear();
        meals.saveAndFlush(meal);
    }

    static MealSummary toSummary(MealPeriod m) {
        return new MealSummary(m.getId(), m.getMealIndex(), m.getMealDate(), m.getMealType(), m.getPhase(),
                m.getState(), m.getExpectedAttendance());
    }

    public static MealDetail toDetail(MealPeriod m) {
        List<MenuItemResponse> items = m.getMenuItems().stream()
                .sorted(Comparator.comparingInt(MenuItem::getStandardSlot))
                .map(i -> new MenuItemResponse(i.getId(), i.getDish().getId(), i.getDish().getName(),
                        i.getDish().getCategory(), i.getRole(), i.getStandardSlot(), i.getCarbonFactorSnapshot(),
                        i.getPortionMassSnapshot(), i.getCarbonScore()))
                .toList();
        return new MealDetail(m.getId(), m.getSemester().getId(), m.getMealIndex(), m.getMealDate(), m.getMealType(),
                m.getPhase(), m.getState(), m.getEvalStatus(), m.isForceP0(), m.getExpectedAttendance(), items);
    }
}