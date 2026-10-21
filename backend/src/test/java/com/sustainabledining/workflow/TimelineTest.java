package com.sustainabledining.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(WorkflowTest.FakeConfig.class)
class TimelineTest {
    @Autowired MockMvc mvc;
    @Autowired FakeMlGateway fake;
    @Autowired DishRepository dishes;
    @Autowired SemesterService semesters;
    @Autowired MealPeriodRepository meals;
    @Autowired MealService mealService;
    @Autowired WorkflowService workflow;

    @Test
    void theTimelineListsEveryMealWithWhatWasDeployedAndHowItScored() throws Exception {
        fake.reset();
        Dish chicken = dishes.save(new Dish("Chicken Curry", "non_veg", new BigDecimal("1.60"), new BigDecimal("150"), true));
        Dish dal = dishes.save(new Dish("Dal Tadka", "pulse", new BigDecimal("0.35"), new BigDecimal("180"), true));
        Dish rice = dishes.save(new Dish("Rice", "staple", new BigDecimal("0.25"), new BigDecimal("200"), false));
        List<PlanItem> menu = List.of(new PlanItem(chicken.getId(), ItemRole.ELIGIBLE, 1),
                new PlanItem(dal.getId(), ItemRole.ELIGIBLE, 2), new PlanItem(rice.getId(), ItemRole.FIXED, 4));
        UUID semesterId = semesters.create(new CreateSemesterRequest("Timeline", SemesterMode.LIVE,
                LocalDate.of(2026, 7, 6), 1000, null, null, null, null, null)).id();

        fake.nextEvents = List.of(FakeMlGateway.event("ALERT_ONLY", List.of()));
        for (int t = 0; t < 2; t++) {
            UUID id = meals.findBySemesterIdAndMealIndex(semesterId, t).orElseThrow().getId();
            mealService.plan(id, new PlanRequest(820, menu));
            workflow.recommend(id);
            workflow.deploy(id, new DeployRequest(null, "tester", null));
            List<ObservedItem> items = mealService.get(id).items().stream()
                    .map(i -> new ObservedItem(i.id(), 200, null)).toList();
            workflow.observe(id, new ObservationRequest(800, new BigDecimal("24000"), items, "tester"), "MANUAL");
            workflow.evaluate(id);
        }

        mvc.perform(get("/api/semesters/" + semesterId + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(336))
                .andExpect(jsonPath("$[0].state").value("EVALUATED"))
                .andExpect(jsonPath("$[0].source").value("BASELINE"))
                .andExpect(jsonPath("$[0].labels[0]").value("P0"))
                .andExpect(jsonPath("$[0].wasteGPerAttendee").value(30.0))
                .andExpect(jsonPath("$[0].safetyEvents").value(1))
                .andExpect(jsonPath("$[0].rollback").value(false))
                .andExpect(jsonPath("$[1].safetyEvents").value(0))
                .andExpect(jsonPath("$[2].state").value("PLANNED"))
                .andExpect(jsonPath("$[2].labels.length()").value(0))
                .andExpect(jsonPath("$[2].reward").doesNotExist());
    }
}