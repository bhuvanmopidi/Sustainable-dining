package com.sustainabledining.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
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
@Transactional   // each test rolls back
class DishControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String dish(String name, String carbon, String mass) {
        return """
            {"name": "%s", "category": "pulse", "carbonKgPerServing": %s, "portionMassG": %s, "eligibleByDefault": true}
            """.formatted(name, carbon, mass);
    }

    private String create(String name) throws Exception {
        String body = mvc.perform(post("/api/dishes").contentType(MediaType.APPLICATION_JSON).content(dish(name, "0.35", "180")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return node.get("id").asText();
    }

    @Test
    void createThenGet() throws Exception {
        String id = create("Dal Tadka");
        mvc.perform(get("/api/dishes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dal Tadka"))
                .andExpect(jsonPath("$.carbonKgPerServing").value(0.35))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void invalidValuesAreRejectedWithFieldDetails() throws Exception {
        mvc.perform(post("/api/dishes").contentType(MediaType.APPLICATION_JSON).content(dish("", "-1", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'carbonKgPerServing')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'portionMassG')]").exists());
    }

    @Test
    void duplicateNameIsAConflictIgnoringCase() throws Exception {
        create("Rajma");
        mvc.perform(post("/api/dishes").contentType(MediaType.APPLICATION_JSON).content(dish("rajma", "0.3", "180")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_NAME"));
    }

    @Test
    void deleteIsSoft() throws Exception {
        String id = create("Upma");
        mvc.perform(delete("/api/dishes/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/dishes"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());
        mvc.perform(get("/api/dishes").param("includeInactive", "true"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].active").value(false));
        mvc.perform(get("/api/dishes/" + id)).andExpect(status().isOk());
    }

    @Test
    void unknownDishIs404() throws Exception {
        mvc.perform(get("/api/dishes/00000000-0000-4000-8000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}