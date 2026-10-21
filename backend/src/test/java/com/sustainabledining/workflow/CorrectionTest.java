package com.sustainabledining.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.meal.*;
import com.sustainabledining.meal.MealDtos.PlanItem;
import com.sustainabledining.meal.MealDtos.PlanRequest;
import com.sustainabledining.semester.SemesterDtos.CreateSemesterRequest;
import com.sustainabledining.semester.SemesterMode;
import com.sustainabledining.semester.SemesterService;
import com.sustainabledining.workflow.WorkflowDtos.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(WorkflowTest.FakeConfig.class)
class CorrectionTest {
    static final String NORMAL = "24000";   // 30 g per attendee at 800 attendees
    static final String HIGH = "48000";     // 60 g per attendee

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FakeMlGateway fake;
    @Autowired DishRepository dishes;
    @Autowired SemesterService semesters;
    @Autowired MealPeriodRepository meals;
    @Autowired MealService mealService;
    @Autowired WorkflowService workflow;
    @Autowired CorrectionService corrections;
    @Autowired CooldownRepository cooldowns;
    @Autowired MealMetricsRepository metrics;
    @Autowired SafetyEventRepository events;

    UUID semesterId;
    List<PlanItem> menu;

    @BeforeEach
    void setUp() {
        fake.reset();
        Dish chicken = dishes.save(new Dish("Chicken Curry", "non_veg", new BigDecimal("1.60"), new BigDecimal("150"), true));
        Dish paneer = dishes.save(new Dish("Paneer Masala", "paneer", new BigDecimal("0.90"), new BigDecimal("150"), true));
        Dish dal = dishes.save(new Dish("Dal Tadka", "pulse", new BigDecimal("0.35"), new BigDecimal("180"), true));
        Dish rice = dishes.save(new Dish("Rice", "staple", new BigDecimal("0.25"), new BigDecimal("200"), false));
        menu = List.of(new PlanItem(chicken.getId(), ItemRole.ELIGIBLE, 1), new PlanItem(paneer.getId(), ItemRole.ELIGIBLE, 2),
                new PlanItem(dal.getId(), ItemRole.ELIGIBLE, 3), new PlanItem(rice.getId(), ItemRole.FIXED, 4));
        semesterId = semesters.create(new CreateSemesterRequest("Corrections", SemesterMode.LIVE, LocalDate.of(2026, 7, 6),
                1000, null, null, null, null, null)).id();
    }

    private UUID mealId(int index) {
        return meals.findBySemesterIdAndMealIndex(semesterId, index).orElseThrow().getId();
    }

    private List<ObservedItem> items(UUID mealId) {
        return mealService.get(mealId).items().stream().map(i -> new ObservedItem(i.id(), 200, null)).toList();
    }

    private EvaluationView runMeal(int index, String waste) {
        UUID id = mealId(index);
        mealService.plan(id, new PlanRequest(820, menu));
        workflow.recommend(id);
        workflow.deploy(id, new DeployRequest(null, "tester", null));
        workflow.observe(id, new ObservationRequest(800, new BigDecimal(waste), items(id), "tester"), "MANUAL");
        return workflow.evaluate(id);
    }

    private void runMeals(int fromInclusive, int toExclusive) {
        for (int t = fromInclusive; t < toExclusive; t++) {
            runMeal(t, NORMAL);
        }
    }

    private CorrectionRequest correction(int index, String waste) {
        UUID id = mealId(index);
        return new CorrectionRequest(800, new BigDecimal(waste), items(id), "auditor", "scale was not tared");
    }

    private List<Cooldown> activeCooldowns() {
        return cooldowns.findBySemesterIdAndRemainingOccurrencesGreaterThan(semesterId, 0);
    }

    @Test
    void aCorrectionAddsARevisionAndRescoresThatMealAndLaterOnes() {
        runMeals(0, 45);                                             // 42 baseline + 3 warm-up meals
        CorrectionView view = corrections.correct(mealId(42), correction(42, HIGH));

        assertThat(view.revision()).isEqualTo(2);
        assertThat(view.replayedMeals()).isEqualTo(3);                // meals 42, 43, 44
        assertThat(view.changes()).hasSize(1);                        // only meal 42's reward changed
        assertThat(view.changes().get(0).rewardBefore()).isCloseTo(0.04, within(1e-9));
        assertThat(view.changes().get(0).rewardAfter()).isCloseTo(-0.96, within(1e-9));
        assertThat(metrics.findByMealIdAndSupersededFalse(mealId(42)).orElseThrow().getReward())
                .isCloseTo(-0.96, within(1e-9));
        List<MealMetrics> all42 = metrics.findByMealIdOrderByCreatedAtAsc(mealId(42));
        assertThat(all42).hasSize(2);
        assertThat(all42.stream().filter(MealMetrics::isSuperseded).count()).isEqualTo(1);   // the old row is kept
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLearnerSeesTheCorrectedReward() {
        runMeals(0, 45);
        corrections.correct(mealId(42), correction(42, HIGH));
        UUID next = mealId(45);
        mealService.plan(next, new PlanRequest(820, menu));
        workflow.recommend(next);
        List<Map<String, Object>> log = (List<Map<String, Object>>) fake.lastRecommend.get("learner_log");
        assertThat(log).hasSize(3);
        assertThat(((Number) log.get(0).get("reward")).doubleValue()).isCloseTo(-0.96, within(1e-9));
    }

    @Test
    void correctingASpikeAwayLiftsTheCooldownAndTheForcedP0() {
        fake.spikeAbove = 50.0;
        runMeals(0, 42);
        runMeal(42, HIGH);                                           // warm-up breakfast with P2/P3: rollback
        assertThat(activeCooldowns()).hasSize(2);
        assertThat(meals.findById(mealId(45)).orElseThrow().isForceP0()).isTrue();

        CorrectionView view = corrections.correct(mealId(42), correction(42, NORMAL));

        assertThat(view.changes().get(0).safetyRulesBefore()).containsExactly("WASTE_SPIKE");
        assertThat(view.changes().get(0).safetyRulesAfter()).isEmpty();
        assertThat(view.activeCooldowns()).isEmpty();
        assertThat(view.forcedP0Meals()).isEmpty();
        assertThat(activeCooldowns()).isEmpty();
        assertThat(meals.findById(mealId(45)).orElseThrow().isForceP0()).isFalse();
        assertThat(events.findByMealIdAndSupersededFalse(mealId(42))).isEmpty();
    }

    @Test
    void correctingASpikeInRebuildsTheCooldownWithTheRightCountdown() {
        fake.spikeAbove = 50.0;
        runMeals(0, 46);                                             // meal 45 is the next breakfast after 42
        assertThat(activeCooldowns()).isEmpty();

        CorrectionView view = corrections.correct(mealId(42), correction(42, HIGH));

        // Breakfast 42 starts a 3-meal cooldown; breakfast 45 has already used one of them.
        assertThat(view.activeCooldowns()).hasSize(2)
                .allSatisfy(c -> assertThat(c.remainingOccurrences()).isEqualTo(2));
        assertThat(view.activeCooldowns()).allSatisfy(c -> assertThat(c.mealType()).isEqualTo(MealType.BREAKFAST));
        assertThat(view.forcedP0Meals()).isEmpty();                   // breakfast 45 was already served
        assertThat(meals.findById(mealId(48)).orElseThrow().isForceP0()).isFalse();
    }

    @Test
    void aCorrectionOnTheLatestMealForcesTheNextPlannedMealToP0() {
        fake.spikeAbove = 50.0;
        runMeals(0, 43);
        CorrectionView view = corrections.correct(mealId(42), correction(42, HIGH));
        assertThat(view.forcedP0Meals()).containsExactly(45);
        assertThat(meals.findById(mealId(45)).orElseThrow().isForceP0()).isTrue();
    }

    @Test
    void baselineMealsCanBeCorrectedOnlyBeforeTheReferenceIsFitted() throws Exception {
        runMeals(0, 3);
        CorrectionView early = corrections.correct(mealId(0), correction(0, HIGH));
        assertThat(early.replayedMeals()).isEqualTo(3);

        runMeals(3, 42);
        assertThat(semesters.get(semesterId).referenceFitted()).isTrue();
        mvc.perform(post("/api/meals/" + mealId(5) + "/observation/correction").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(correction(5, HIGH))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REFERENCE_FROZEN"));
    }

    @Test
    void correctionsWaitUntilNoMealIsHalfway() throws Exception {
        runMeals(0, 2);
        UUID third = mealId(2);
        mealService.plan(third, new PlanRequest(820, menu));
        workflow.recommend(third);
        mvc.perform(post("/api/meals/" + mealId(0) + "/observation/correction").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(correction(0, HIGH))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MEAL_IN_PROGRESS"));
    }

    @Test
    void onlyEvaluatedMealsCanBeCorrectedAndAReasonIsRequired() throws Exception {
        UUID first = mealId(0);
        mealService.plan(first, new PlanRequest(820, menu));
        mvc.perform(post("/api/meals/" + first + "/observation/correction").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(correction(0, HIGH))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_CORRECTABLE"));

        CorrectionRequest noReason = new CorrectionRequest(800, new BigDecimal(HIGH), items(first), "auditor", " ");
        mvc.perform(post("/api/meals/" + first + "/observation/correction").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noReason)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void theObservationHistoryKeepsEveryRevision() throws Exception {
        runMeals(0, 1);
        mvc.perform(post("/api/meals/" + mealId(0) + "/observation/correction").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(correction(0, HIGH))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.replayedMeals").value(1));

        mvc.perform(get("/api/meals/" + mealId(0) + "/observations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[0].correctionReason").doesNotExist())
                .andExpect(jsonPath("$[1].aggregateWasteG").value(48000))
                .andExpect(jsonPath("$[1].correctionReason").value("scale was not tared"))
                .andExpect(jsonPath("$[1].current").value(true));
    }
}