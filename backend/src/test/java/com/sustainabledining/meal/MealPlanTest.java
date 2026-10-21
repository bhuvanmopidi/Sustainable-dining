package com.sustainabledining.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.semester.SemesterDtos.CreateSemesterRequest;
import com.sustainabledining.semester.SemesterMode;
import com.sustainabledining.semester.SemesterService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MealPlanTest {
    @Autowired MockMvc mvc;
    @Autowired DishRepository dishes;
    @Autowired SemesterService semesters;
    @Autowired MealPeriodRepository meals;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    Dish chicken, paneer, dal, rice, chapati;
    UUID semesterId, mealId;

    private Dish dish(String name, String category, String carbon, String mass, boolean eligible) {
        return dishes.save(new Dish(name, category, new BigDecimal(carbon), new BigDecimal(mass), eligible));
    }

    @BeforeEach
    void setUp() {
        chicken = dish("Chicken Curry", "non_veg", "1.60", "150", true);
        paneer = dish("Paneer Masala", "paneer", "0.90", "150", true);
        dal = dish("Dal Tadka", "pulse", "0.35", "180", true);
        rice = dish("Rice", "staple", "0.25", "200", false);
        chapati = dish("Chapati", "staple", "0.08", "40", false);   // catalogue range: 0.08 .. 1.60
        semesterId = semesters.create(new CreateSemesterRequest("Test", SemesterMode.LIVE, LocalDate.of(2026, 7, 6),
                1000, null, null, null, null, null)).id();
        mealId = meals.findBySemesterIdAndMealIndex(semesterId, 0).orElseThrow().getId();
    }

    private String item(Dish d, String role, int slot) {
        return "{\"dishId\": \"" + d.getId() + "\", \"role\": \"" + role + "\", \"standardSlot\": " + slot + "}";
    }

    private String plan(int attendance, String... items) {
        return "{\"expectedAttendance\": " + attendance + ", \"items\": [" + String.join(",", items) + "]}";
    }

    private String fullMenu() {
        return plan(820, item(chicken, "ELIGIBLE", 1), item(paneer, "ELIGIBLE", 2), item(dal, "ELIGIBLE", 3),
                item(rice, "FIXED", 4), item(chapati, "FIXED", 5));
    }

    @Test
    void planSavesTheMenuWithSnapshotsAndScores() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON).content(fullMenu()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAttendance").value(820))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].dishName").value("Chicken Curry"))
                .andExpect(jsonPath("$.items[0].carbonFactorSnapshot").value(1.6))
                .andExpect(jsonPath("$.items[0].carbonScore").value(1.0))           // catalogue max
                .andExpect(jsonPath("$.items[2].carbonScore").value(0.177632))      // (0.35-0.08)/(1.60-0.08)
                .andExpect(jsonPath("$.items[4].carbonScore").value(0.0))           // catalogue min
                .andExpect(jsonPath("$.items[4].role").value("FIXED"));
    }

    @Test
    void editingADishLaterDoesNotChangeThePlannedMenu() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON).content(fullMenu()))
                .andExpect(status().isOk());
        chicken.update("Chicken Curry", "non_veg", new BigDecimal("2.50"), new BigDecimal("150"), true);
        em.flush();
        em.clear();
        mvc.perform(get("/api/meals/" + mealId))
                .andExpect(jsonPath("$.items[0].carbonFactorSnapshot").value(1.6));
    }

    @Test
    void replanningReplacesTheMenu() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON).content(fullMenu()))
                    .andExpect(status().isOk());
        }
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM menu_item WHERE meal_id = ?", Integer.class, mealId);
        assertThat(rows).isEqualTo(5);
    }

    @Test
    void eligibleItemInAFixedSlotIsRejected() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(plan(800, item(chicken, "ELIGIBLE", 4))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_MENU"))
                .andExpect(jsonPath("$.details[?(@.field == 'items[0].role')]").exists());
    }

    @Test
    void duplicateSlotsAndDishesAreRejected() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(plan(800, item(chicken, "ELIGIBLE", 1), item(chicken, "ELIGIBLE", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.field == 'items[1].standardSlot')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'items[1].dishId')]").exists());
    }

    @Test
    void attendanceAboveEnrolmentIsRejected() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(plan(1200, item(chicken, "ELIGIBLE", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.field == 'expectedAttendance')]").exists());
    }

    @Test
    void inactiveDishIsRejected() throws Exception {
        dal.deactivate();
        em.flush();
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON)
                        .content(plan(800, item(dal, "ELIGIBLE", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].message").value("dish is inactive"));
    }

    @Test
    void emptyMenuIs400() throws Exception {
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON).content(plan(800)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyPlannedMealsCanBeChanged() throws Exception {
        MealPeriod meal = meals.findById(mealId).orElseThrow();
        meal.transitionTo(MealState.RECOMMENDED);
        meals.saveAndFlush(meal);
        mvc.perform(put("/api/meals/" + mealId + "/plan").contentType(MediaType.APPLICATION_JSON).content(fullMenu()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_PLANNABLE"));
    }
}