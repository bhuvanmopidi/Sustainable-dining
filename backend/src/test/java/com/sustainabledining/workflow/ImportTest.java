package com.sustainabledining.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sustainabledining.catalog.Dish;
import com.sustainabledining.catalog.DishRepository;
import com.sustainabledining.meal.*;
import com.sustainabledining.meal.MealDtos.MealDetail;
import com.sustainabledining.semester.SemesterDtos.CreateSemesterRequest;
import com.sustainabledining.semester.SemesterMode;
import com.sustainabledining.semester.SemesterService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(WorkflowTest.FakeConfig.class)
class ImportTest {
    static final List<String> DISHES = List.of("Chicken Curry", "paneer masala", "Dal Tadka", "Rice");   // mixed case on purpose
    static final List<String> TYPES = List.of("BREAKFAST", "LUNCH", "DINNER");

    @Autowired MockMvc mvc;
    @Autowired FakeMlGateway fake;
    @Autowired DishRepository dishes;
    @Autowired SemesterService semesters;
    @Autowired MealPeriodRepository meals;
    @Autowired MealService mealService;
    @Autowired ObservationRepository observations;

    UUID semesterId;

    @BeforeEach
    void setUp() {
        fake.reset();
        dishes.save(new Dish("Chicken Curry", "non_veg", new BigDecimal("1.60"), new BigDecimal("150"), true));
        dishes.save(new Dish("Paneer Masala", "paneer", new BigDecimal("0.90"), new BigDecimal("150"), true));
        dishes.save(new Dish("Dal Tadka", "pulse", new BigDecimal("0.35"), new BigDecimal("180"), true));
        dishes.save(new Dish("Rice", "staple", new BigDecimal("0.25"), new BigDecimal("200"), false));
        semesterId = semesters.create(new CreateSemesterRequest("Imports", SemesterMode.LIVE, LocalDate.of(2026, 7, 6),
                1000, null, null, null, null, null)).id();
    }

    /** Menus for the first `days` days: three curries in slots 1-3 and rice (role left blank, so FIXED) in slot 4. */
    static String menusCsv(int days) {
        StringBuilder sb = new StringBuilder("date,meal_type,dish,slot,expected_attendance,role\r\n");
        for (int d = 0; d < days; d++) {
            String date = LocalDate.of(2026, 7, 6).plusDays(d).toString();
            for (String type : TYPES) {
                for (int i = 0; i < DISHES.size(); i++) {
                    sb.append(date).append(',').append(type).append(',').append(DISHES.get(i)).append(',')
                            .append(i + 1).append(",820,\r\n");
                }
            }
        }
        return sb.toString();
    }

    static String observationsCsv(int days) {
        StringBuilder sb = new StringBuilder("date,meal_type,dish,portions_taken,waste_g,attendance,total_waste_g\n");
        for (int d = 0; d < days; d++) {
            String date = LocalDate.of(2026, 7, 6).plusDays(d).toString();
            for (String type : TYPES) {
                for (String dish : DISHES) {
                    sb.append(date).append(',').append(type).append(",\"").append(dish).append("\",200,,800,24000\n");
                }
            }
        }
        return sb.toString();
    }

    private ResultActions postCsv(String kind, String csv, String... params) throws Exception {
        var req = post("/api/semesters/" + semesterId + "/import/" + kind).contentType("text/csv")
                .content(csv.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req);
    }

    private MealDetail meal(int index) {
        return mealService.get(meals.findBySemesterIdAndMealIndex(semesterId, index).orElseThrow().getId());
    }

    @Test
    void aMenuFilePlansEveryMealItNames() throws Exception {
        postCsv("menus", menusCsv(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsRead").value(24))
                .andExpect(jsonPath("$.mealsAffected").value(6))
                .andExpect(jsonPath("$.meals[0].action").value("PLANNED"));

        MealDetail first = meal(0);
        assertThat(first.items()).hasSize(4);
        assertThat(first.expectedAttendance()).isEqualTo(820);
        assertThat(first.items().get(3).dishName()).isEqualTo("Rice");
        assertThat(first.items().get(3).role()).isEqualTo(ItemRole.FIXED);   // from the dish default
        assertThat(meal(6).items()).isEmpty();                                // day 3 was not in the file
    }

    @Test
    void anUnknownDishRejectsTheWholeFileWithItsLineNumber() throws Exception {
        String csv = menusCsv(1) + "2026-07-06,DINNER,Biryani,5,820,\r\n";
        postCsv("menus", csv)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IMPORT_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("line 14: dish"));
        assertThat(meal(0).items()).isEmpty();
    }

    @Test
    void aDryRunChecksButSavesNothing() throws Exception {
        postCsv("menus", menusCsv(1), "dryRun", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.meals[0].action").value("WOULD_PLAN"));
        assertThat(meal(0).items()).isEmpty();
    }

    @Test
    void aFormUploadWorksToo() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "menus.csv", "text/csv",
                menusCsv(1).getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/semesters/" + semesterId + "/import/menus").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mealsAffected").value(3));
        assertThat(meal(2).items()).hasSize(4);
    }

    @Test
    void baselineObservationsAreDeployedNeutralObservedAndEvaluated() throws Exception {
        postCsv("menus", menusCsv(2)).andExpect(status().isOk());
        postCsv("observations", observationsCsv(2), "enteredBy", "mess-office")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mealsAffected").value(6))
                .andExpect(jsonPath("$.meals[0].action").value("DEPLOYED_NEUTRAL_AND_OBSERVED"))
                .andExpect(jsonPath("$.meals[5].state").value("EVALUATED"));

        assertThat(meal(5).state()).isEqualTo(MealState.EVALUATED);
        assertThat(meal(6).state()).isEqualTo(MealState.PLANNED);
        Observation obs = observations.findFirstByMealIdOrderByRevisionDesc(meal(0).id()).orElseThrow();
        assertThat(obs.getSource()).isEqualTo("CSV");
        assertThat(obs.getEnteredBy()).isEqualTo("mess-office");
        assertThat(obs.getItems()).hasSize(4);
        assertThat(fake.lastRecommend.get("phase")).isEqualTo("BASELINE");
    }

    @Test
    void observationsMustMatchTheMenuAndAgreeWithinAMeal() throws Exception {
        postCsv("menus", menusCsv(1)).andExpect(status().isOk());
        String csv = "date,meal_type,dish,portions_taken,attendance,total_waste_g\n"
                + "2026-07-06,BREAKFAST,Chicken Curry,200,800,24000\n"
                + "2026-07-06,BREAKFAST,Paneer Masala,200,800,24000\n"
                + "2026-07-06,BREAKFAST,Dal Tadka,200,799,24000\n"      // attendance disagrees
                + "2026-07-06,BREAKFAST,Fish Curry,10,800,24000\n";     // not on the menu, and Rice is missing
        postCsv("observations", csv)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IMPORT_INVALID"))
                .andExpect(jsonPath("$.details.length()").value(3));
        assertThat(meal(0).state()).isEqualTo(MealState.PLANNED);
    }

    @Test
    void anEvaluatedMealPointsToTheCorrectionEndpoint() throws Exception {
        postCsv("menus", menusCsv(1)).andExpect(status().isOk());
        postCsv("observations", observationsCsv(1)).andExpect(status().isOk());
        postCsv("observations", observationsCsv(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].message").value(org.hamcrest.Matchers.containsString("/observation/correction")));
    }

    @Test
    void malformedCsvIsReported() throws Exception {
        postCsv("menus", "date,meal_type,dish,slot,expected_attendance\n2026-07-06,BREAKFAST,\"Rice,4,820\n")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CSV_MALFORMED"));
        postCsv("menus", "date,dish\n2026-07-06,Rice\n")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IMPORT_INVALID"))
                .andExpect(jsonPath("$.details.length()").value(3));   // meal_type, slot, expected_attendance
    }

    @Test
    void demoSemestersCannotImport() throws Exception {
        UUID demo = semesters.create(new CreateSemesterRequest("Demo", SemesterMode.SIMULATED_DEMO, LocalDate.of(2026, 7, 6),
                1000, null, null, null, 1, "SUCCESSFUL_NUDGING")).id();
        mvc.perform(post("/api/semesters/" + demo + "/import/menus").contentType("text/csv")
                        .content(menusCsv(1).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_LIVE"));
    }
}