package com.sustainabledining.demo;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.integration.SimGateway;
import com.sustainabledining.integration.SimOutOfOrderException;
import com.sustainabledining.meal.MealDtos.MealDetail;
import com.sustainabledining.meal.MealDtos.MenuItemResponse;
import com.sustainabledining.meal.MealPeriod;
import com.sustainabledining.meal.MealPeriodRepository;
import com.sustainabledining.meal.MealService;
import com.sustainabledining.meal.MealState;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.semester.SemesterMode;
import com.sustainabledining.semester.SemesterService;
import com.sustainabledining.workflow.WorkflowDtos.*;
import com.sustainabledining.workflow.WorkflowService;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Drives a SIMULATED_DEMO semester: the simulator plays the mess (menus and outcomes), the real workflow does
 * everything else. Not transactional itself: each workflow step commits on its own, like a real manager's clicks.
 */
@Service
public class DemoService {
    private final SemesterService semesters;
    private final MealPeriodRepository meals;
    private final MealService mealService;
    private final WorkflowService workflow;
    private final SimGateway sim;

    public DemoService(SemesterService semesters, MealPeriodRepository meals, MealService mealService,
                       WorkflowService workflow, SimGateway sim) {
        this.semesters = semesters;
        this.meals = meals;
        this.mealService = mealService;
        this.workflow = workflow;
        this.sim = sim;
    }

    public List<EvaluationView> run(UUID semesterId, int count) {
        List<EvaluationView> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EvaluationView v = runNext(semesterId);
            if (v == null) break;
            out.add(v);
        }
        return out;
    }

    /** Runs the next unfinished meal to EVALUATED, resuming from whatever state it is in. Null when complete. */
    @SuppressWarnings("unchecked")
    public EvaluationView runNext(UUID semesterId) {
        Semester s = semesters.load(semesterId);
        if (s.getMode() != SemesterMode.SIMULATED_DEMO) {
            throw ApiException.conflict("NOT_A_DEMO", "only SIMULATED_DEMO semesters can be run by the simulator");
        }
        Optional<MealPeriod> next = meals.findFirstBySemesterIdAndStateNotOrderByMealIndexAsc(semesterId, MealState.EVALUATED);
        if (next.isEmpty()) {
            return null;
        }
        UUID mealId = next.get().getId();
        int t = next.get().getMealIndex();
        MealState state = next.get().getState();
        Map<String, Object> spec = sim.mealSpec(s.getSimSeed(), s.getSimScenario(), t);
        Map<String, Object> simMeal = (Map<String, Object>) spec.get("meal");
        if (((Number) simMeal.get("enrolled")).intValue() != s.getEnrolledStudents()
                || !String.valueOf(simMeal.get("date")).equals(next.get().getMealDate().toString())) {
            throw ApiException.unprocessable("DEMO_MISMATCH", "a demo semester must match the simulator: start on "
                    + simMeal.get("date") + " (for meal " + t + ") with " + simMeal.get("enrolled") + " enrolled students");
        }

        if (state == MealState.PLANNED) {
            if (t == 0) {
                sim.reset(s.getSimSeed(), s.getSimScenario());
            }
            mealService.planFromSimulator(mealId, spec);
            workflow.recommend(mealId);
            state = MealState.RECOMMENDED;
        }
        if (state == MealState.RECOMMENDED) {
            workflow.deploy(mealId, new DeployRequest(null, "demo-simulator", null));
            state = MealState.DEPLOYED;
        }
        if (state == MealState.DEPLOYED) {
            observeFromSimulator(s, mealId, t, spec);
        }
        return workflow.evaluate(mealId);
    }

    @SuppressWarnings("unchecked")
    private void observeFromSimulator(Semester s, UUID mealId, int t, Map<String, Object> spec) {
        Map<String, String> javaToSim = idMap(mealService.get(mealId), spec);
        Map<String, String> simToJava = new HashMap<>();
        javaToSim.forEach((j, si) -> simToJava.put(si, j));
        Map<String, Object> request = outcomeRequest(s, t, spec, javaToSim, workflow.deployedOrdering(mealId));
        Map<String, Object> obs;
        try {
            obs = sim.outcome(request);
        } catch (SimOutOfOrderException e) {
            resync(s, t);   // simulator was restarted or used by another semester: replay earlier meals
            obs = sim.outcome(request);
        }
        List<ObservedItem> items = new ArrayList<>();
        for (Object o : (List<Object>) obs.get("item_outcomes")) {
            Map<String, Object> it = (Map<String, Object>) o;
            items.add(new ObservedItem(UUID.fromString(simToJava.get(String.valueOf(it.get("menu_item_id")))),
                    ((Number) it.get("portions_taken")).intValue(), null));
        }
        workflow.observe(mealId, new ObservationRequest(((Number) obs.get("actual_attendance")).intValue(),
                new BigDecimal(String.valueOf(obs.get("aggregate_waste_g"))), items, "demo-simulator"), "SIMULATED");
    }

    /** Replays meals 0..t-1 into a fresh simulator using the layouts that were actually deployed. */
    private void resync(Semester s, int t) {
        sim.reset(s.getSimSeed(), s.getSimScenario());
        for (int k = 0; k < t; k++) {
            MealPeriod m = meals.findBySemesterIdAndMealIndex(s.getId(), k).orElseThrow();
            Map<String, Object> spec = sim.mealSpec(s.getSimSeed(), s.getSimScenario(), k);
            sim.outcome(outcomeRequest(s, k, spec, idMap(mealService.get(m.getId()), spec),
                    workflow.deployedOrdering(m.getId())));
        }
    }

    private static Map<String, Object> outcomeRequest(Semester s, int t, Map<String, Object> spec,
                                                      Map<String, String> javaToSim, List<String> javaOrdering) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("seed", s.getSimSeed());
        req.put("scenario", s.getSimScenario());
        req.put("meal_index", t);
        req.put("meal", spec.get("meal"));
        req.put("menu_items", spec.get("menu_items"));
        req.put("deployed_ordering", javaOrdering.stream().map(javaToSim::get).toList());
        return req;
    }

    /** Java menu-item id -> simulator menu-item id, matched by dish name. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> idMap(MealDetail detail, Map<String, Object> spec) {
        Map<String, Object> names = (Map<String, Object>) spec.get("dish_names");
        Map<String, String> simItemByName = new HashMap<>();
        for (Object o : (List<Object>) spec.get("menu_items")) {
            Map<String, Object> it = (Map<String, Object>) o;
            String name = String.valueOf(names.get(String.valueOf(it.get("dish_id"))));
            simItemByName.put(name.toLowerCase(Locale.ROOT), String.valueOf(it.get("id")));
        }
        Map<String, String> out = new HashMap<>();
        for (MenuItemResponse item : detail.items()) {
            String simId = simItemByName.get(item.dishName().toLowerCase(Locale.ROOT));
            if (simId == null) {
                throw ApiException.unprocessable("DEMO_MISMATCH", "menu item " + item.dishName() + " is not on the simulator's menu");
            }
            out.put(item.id().toString(), simId);
        }
        return out;
    }
}