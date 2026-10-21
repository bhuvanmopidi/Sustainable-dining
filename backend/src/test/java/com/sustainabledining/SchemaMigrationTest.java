package com.sustainabledining;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void allOperationalTablesExist() {
        List<String> expected = List.of("experiment_config", "dish", "semester", "serving_slot", "meal_period",
                "menu_item", "candidate_layout", "reference_model", "recommendation", "deployment", "observation",
                "item_outcome", "meal_metrics", "safety_event", "cooldown", "forecast");
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(actual).containsAll(expected);
    }

    @Test
    void groundTruthHasNoTable() {
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(actual).noneMatch(t -> t.contains("preference") || t.contains("dissatisfaction") || t.contains("oracle"));
    }
}