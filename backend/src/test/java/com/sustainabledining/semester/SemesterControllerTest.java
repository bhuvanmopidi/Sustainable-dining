package com.sustainabledining.semester;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SemesterControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final String LIVE = """
        {"name": "Monsoon 2026", "mode": "LIVE", "startDate": "2026-07-06", "enrolledStudents": 1000}
        """;

    private String create(String body) throws Exception {
        String json = mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    @Test
    void creatingASemesterGeneratesTheWholeSchedule() throws Exception {
        mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content(LIVE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configVersion").value("v1"))
                .andExpect(jsonPath("$.mealCount").value(336))
                .andExpect(jsonPath("$.mealsByPhase.BASELINE").value(42))
                .andExpect(jsonPath("$.mealsByPhase.WARMUP").value(21))
                .andExpect(jsonPath("$.mealsByPhase.ADAPTIVE").value(273))
                .andExpect(jsonPath("$.slots.length()").value(5))
                .andExpect(jsonPath("$.slots[0].visibility").value(1.0))
                .andExpect(jsonPath("$.slots[1].visibility").value(0.6))
                .andExpect(jsonPath("$.slots[2].visibility").value(0.2))
                .andExpect(jsonPath("$.slots[3].eligibleSlot").value(false))
                .andExpect(jsonPath("$.referenceFitted").value(false));
    }

    @Test
    void mealsFollowTheHarnessIndexing() throws Exception {
        String id = create(LIVE);
        mvc.perform(get("/api/semesters/" + id + "/meals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(336))
                .andExpect(jsonPath("$[0].mealType").value("BREAKFAST"))
                .andExpect(jsonPath("$[0].date").value("2026-07-06"))
                .andExpect(jsonPath("$[1].mealType").value("LUNCH"))
                .andExpect(jsonPath("$[2].mealType").value("DINNER"))
                .andExpect(jsonPath("$[3].date").value("2026-07-07"))
                .andExpect(jsonPath("$[41].phase").value("BASELINE"))
                .andExpect(jsonPath("$[42].phase").value("WARMUP"))
                .andExpect(jsonPath("$[63].phase").value("ADAPTIVE"))
                .andExpect(jsonPath("$[0].state").value("PLANNED"));
    }

    @Test
    void nextMealStartsAtTheFirstMeal() throws Exception {
        String id = create(LIVE);
        mvc.perform(get("/api/semesters/" + id + "/meals/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mealIndex").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void demoModeNeedsAValidSeedAndScenario() throws Exception {
        String noSeed = """
            {"name": "Demo", "mode": "SIMULATED_DEMO", "startDate": "2026-07-06", "enrolledStudents": 1000}
            """;
        String badScenario = """
            {"name": "Demo", "mode": "SIMULATED_DEMO", "startDate": "2026-07-06", "enrolledStudents": 1000,
             "simSeed": 100, "simScenario": "MADE_UP"}
            """;
        String liveWithSeed = """
            {"name": "Live", "mode": "LIVE", "startDate": "2026-07-06", "enrolledStudents": 1000, "simSeed": 1}
            """;
        for (String body : new String[] {noSeed, badScenario, liveWithSeed}) {
            mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("INVALID_MODE"));
        }
        String ok = """
            {"name": "Demo", "mode": "SIMULATED_DEMO", "startDate": "2026-07-06", "enrolledStudents": 1000,
             "simSeed": 100, "simScenario": "FAILED_SUBSTITUTION"}
            """;
        mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.simScenario").value("FAILED_SUBSTITUTION"));
    }

    @Test
    void unknownConfigVersionIs404() throws Exception {
        String body = """
            {"name": "X", "mode": "LIVE", "startDate": "2026-07-06", "enrolledStudents": 1000, "configVersion": "v99"}
            """;
        mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mvc.perform(post("/api/semesters").contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"LIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'startDate')]").exists());
    }
}