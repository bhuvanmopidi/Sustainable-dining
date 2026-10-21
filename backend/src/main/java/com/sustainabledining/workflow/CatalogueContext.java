package com.sustainabledining.workflow;

import com.sustainabledining.catalog.CarbonScorer;
import com.sustainabledining.integration.SimGateway;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.semester.SemesterMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Catalogue-wide median carbon score. Demo semesters use the simulator's catalogue so results match the harness. */
@Component
public class CatalogueContext {
    private final CarbonScorer scorer;
    private final SimGateway sim;
    private final Map<UUID, Double> demoMedians = new ConcurrentHashMap<>();

    public CatalogueContext(CarbonScorer scorer, SimGateway sim) {
        this.scorer = scorer;
        this.sim = sim;
    }

    public double median(Semester semester) {
        if (semester.getMode() == SemesterMode.SIMULATED_DEMO) {
            return demoMedians.computeIfAbsent(semester.getId(), id -> ((Number) sim
                    .mealSpec(semester.getSimSeed(), semester.getSimScenario(), 0)
                    .get("catalogue_median_carbon_score")).doubleValue());
        }
        return scorer.median().doubleValue();
    }
}