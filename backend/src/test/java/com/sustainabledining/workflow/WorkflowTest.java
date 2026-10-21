package com.sustainabledining.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.integration.MlGateway;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(WorkflowTest.FakeConfig.class)
class WorkflowTest {

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeMlGateway fakeMlGateway() {
            return new FakeMlGateway();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FakeMlGateway fake;
    @Autowired DishRepository dishes;
    @Autowired SemesterService semesters;
    @Autowired MealPeriodRepository meals;
    @Autowired MealService mealService;
    @Autowired WorkflowService workflow;
    @Autowired CooldownRepository cooldowns;
    @Autowired DeploymentRepository deployments;
    @Autowired MlGateway gateway;

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
        semesterId = semesters.create(new CreateSemesterRequest("Test", SemesterMode.LIVE, LocalDate.of(2026, 7, 6),
                1000, null, null, null, null, null)).id();
    }

    private UUID mealId(int index) {
        return meals.findBySemesterIdAndMealIndex(semesterId, index).orElseThrow().getId();
    }

    private ObservationRequest observation(UUID mealId) {
        List<ObservedItem> items = mealService.get(mealId).items().stream()
                .map(i -> new ObservedItem(i.id(), 200, null)).toList();
        return new ObservationRequest(800, new BigDecimal("24000"), items, "tester");
    }

    private EvaluationView runMeal(int index) {
        UUID id = mealId(index);
        mealService.plan(id, new PlanRequest(820, menu));
        workflow.recommend(id);
        workflow.deploy(id, new DeployRequest(null, "tester", null));
        workflow.observe(id, observation(id), "MANUAL");
        return workflow.evaluate(id);
    }

    private String observationJson(UUID mealId) throws Exception {
        return mapper.writeValueAsString(observation(mealId));
    }

    @Test
    void theFakeGatewayIsTheOneInUse() {
        assertThat(gateway).isSameAs(fake);
    }

    @Test
    void aMealMovesThroughEveryStateOverHttp() throws Exception {
        UUID id = mealId(0);
        mealService.plan(id, new PlanRequest(820, menu));

        mvc.perform(post("/api/meals/" + id + "/recommend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECOMMENDED"))
                .andExpect(jsonPath("$.source").value("BASELINE"))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].dishOrder[0]").value("Chicken Curry"));

        mvc.perform(post("/api/meals/" + id + "/deploy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deployedBy\": \"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DEPLOYED"))
                .andExpect(jsonPath("$.override").value(false));

        mvc.perform(post("/api/meals/" + id + "/observation").contentType(MediaType.APPLICATION_JSON)
                        .content(observationJson(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EVALUATED"))
                .andExpect(jsonPath("$.evalStatus").value("OK"))
                .andExpect(jsonPath("$.learnerUpdate").value(false))
                .andExpect(jsonPath("$.reward").doesNotExist());

        mvc.perform(get("/api/meals/" + id)).andExpect(jsonPath("$.state").value("EVALUATED"));
    }

    @Test
    void anOverrideNeedsAReasonAndIsRecorded() throws Exception {
        UUID id = mealId(0);
        mealService.plan(id, new PlanRequest(820, menu));
        RecommendationView rec = workflow.recommend(id);
        UUID other = rec.candidates().stream().filter(c -> !c.recommended()).findFirst().orElseThrow().id();

        mvc.perform(post("/api/meals/" + id + "/deploy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deployedBy\": \"manager\", \"candidateId\": \"" + other + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("OVERRIDE_REASON_REQUIRED"));

        mvc.perform(post("/api/meals/" + id + "/deploy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deployedBy\": \"manager\", \"candidateId\": \"" + other
                                + "\", \"overrideReason\": \"paneer ran out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override").value(true))
                .andExpect(jsonPath("$.candidateId").value(other.toString()));

        assertThat(deployments.findByMealIdAndSupersededFalse(id).orElseThrow().getCandidate().getId()).isEqualTo(other);
    }

    @Test
    void mealsMustRunInOrder() throws Exception {
        UUID second = mealId(1);
        mealService.plan(second, new PlanRequest(820, menu));
        mvc.perform(post("/api/meals/" + second + "/recommend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OUT_OF_ORDER"));
    }

    @Test
    void aRecommendationNeedsAMenu() throws Exception {
        mvc.perform(post("/api/meals/" + mealId(0) + "/recommend"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MENU_REQUIRED"));
    }

    @Test
    void stepsCannotBeSkipped() throws Exception {
        UUID id = mealId(0);
        mealService.plan(id, new PlanRequest(820, menu));
        mvc.perform(post("/api/meals/" + id + "/deploy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deployedBy\": \"manager\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_DEPLOYABLE"));
    }

    @Test
    void theObservationMustCoverEveryMenuItem() throws Exception {
        UUID id = mealId(0);
        mealService.plan(id, new PlanRequest(820, menu));
        workflow.recommend(id);
        workflow.deploy(id, new DeployRequest(null, "tester", null));
        ObservationRequest full = observation(id);
        ObservationRequest partial = new ObservationRequest(800, new BigDecimal("24000"),
                full.items().subList(0, 3), "tester");
        mvc.perform(post("/api/meals/" + id + "/observation").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(partial)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_OBSERVATION"));
    }

    @Test
    void aRollbackForcesP0AndCoolsDownOnlyThatMealType() {
        fake.nextEvents = List.of(FakeMlGateway.event("ROLLBACK", List.of("P2", "P3")));
        runMeal(0);                                                        // breakfast

        assertThat(meals.findById(mealId(3)).orElseThrow().isForceP0()).isTrue();   // next breakfast
        assertThat(meals.findById(mealId(1)).orElseThrow().isForceP0()).isFalse();  // lunch unaffected
        Cooldown p2 = cooldowns.findBySemesterIdAndPolicyLabelAndMealType(semesterId, "P2", MealType.BREAKFAST).orElseThrow();
        assertThat(p2.getRemainingOccurrences()).isEqualTo(3);

        runMeal(1);
        runMeal(2);                                                        // lunch and dinner do not count down
        assertThat(p2.getRemainingOccurrences()).isEqualTo(3);

        EvaluationView breakfast = runMeal(3);
        assertThat(fake.lastRecommend.get("force_p0")).isEqualTo(true);
        assertThat((List<?>) fake.lastRecommend.get("active_cooldowns")).hasSize(2);
        assertThat(breakfast.deployedLabels()).contains("P0");
        assertThat(p2.getRemainingOccurrences()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReferenceIsFittedWhenTheBaselineEndsAndTheLearnerSeesDeployedMeals() {
        for (int t = 0; t < 42; t++) {
            runMeal(t);
        }
        assertThat(fake.fitCalls).isEqualTo(1);
        assertThat(semesters.get(semesterId).referenceFitted()).isTrue();

        EvaluationView firstWarmup = runMeal(42);
        assertThat(fake.lastRecommend.get("phase")).isEqualTo("WARMUP");
        assertThat((List<?>) fake.lastRecommend.get("forecast")).isNotEmpty();
        assertThat(fake.lastRecommend.get("warmup_cursor")).isEqualTo(0);
        assertThat(fake.lastEvaluate.get("reference_model")).isNotNull();
        assertThat(firstWarmup.learnerUpdate()).isTrue();

        runMeal(43);
        runMeal(44);
        UUID next = mealId(45);
        mealService.plan(next, new PlanRequest(820, menu));
        workflow.recommend(next);
        List<Map<String, Object>> log = (List<Map<String, Object>>) fake.lastRecommend.get("learner_log");
        assertThat(log).hasSize(3);                                        // meals 42, 43, 44 (baseline never trains)
        assertThat(log.get(0).get("deployed_labels")).isEqualTo(List.of("P2", "P3"));
        assertThat((List<?>) fake.lastRecommend.get("exposure_history")).hasSize(9);
        assertThat(fake.lastRecommend.get("warmup_cursor")).isEqualTo(1);   // one earlier warm-up breakfast
        assertThat((List<?>) fake.lastEvaluate.get("same_type_history")).hasSize(2);
    }

    @Test
    void safetyEventsCanBeAcknowledged() throws Exception {
        fake.nextEvents = List.of(FakeMlGateway.event("ALERT_ONLY", List.of()));
        runMeal(0);
        String json = mvc.perform(get("/api/semesters/" + semesterId + "/safety-events").param("unacknowledged", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = mapper.readTree(json).get(0);
        mvc.perform(post("/api/safety-events/" + first.get("id").asText() + "/acknowledge")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"by\": \"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedBy").value("manager"));
        mvc.perform(get("/api/semesters/" + semesterId + "/safety-events").param("unacknowledged", "true"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theDemoRunnerRefusesLiveSemesters() throws Exception {
        mvc.perform(post("/api/semesters/" + semesterId + "/demo/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_A_DEMO"));
    }
}