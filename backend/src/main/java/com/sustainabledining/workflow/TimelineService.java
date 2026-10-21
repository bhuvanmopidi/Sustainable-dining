package com.sustainabledining.workflow;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.meal.*;
import com.sustainabledining.semester.SemesterRepository;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One row per meal of a semester with what was deployed and how it scored. Feeds the dashboard charts. */
@Service
@Transactional(readOnly = true)
public class TimelineService {

    public record TimelineRow(UUID mealId, int mealIndex, LocalDate date, MealType mealType, Phase phase,
                              MealState state, EvalStatus evalStatus, boolean forceP0, String source,
                              List<String> labels, Double d, boolean override, Double reward,
                              Double carbonPerAttendee, Double wasteGPerAttendee, Double eligibleUptake,
                              int safetyEvents, boolean rollback) {}

    private final SemesterRepository semesters;
    private final MealPeriodRepository meals;
    private final RecommendationRepository recommendations;
    private final DeploymentRepository deployments;
    private final MealMetricsRepository metrics;
    private final SafetyEventRepository events;

    public TimelineService(SemesterRepository semesters, MealPeriodRepository meals,
                           RecommendationRepository recommendations, DeploymentRepository deployments,
                           MealMetricsRepository metrics, SafetyEventRepository events) {
        this.semesters = semesters;
        this.meals = meals;
        this.recommendations = recommendations;
        this.deployments = deployments;
        this.metrics = metrics;
        this.events = events;
    }

    public List<TimelineRow> timeline(UUID semesterId) {
        if (!semesters.existsById(semesterId)) {
            throw ApiException.notFound("semester", semesterId);
        }
        Map<UUID, Recommendation> recs = new HashMap<>();
        recommendations.findByMealSemesterIdAndSupersededFalse(semesterId).forEach(r -> recs.put(r.getMeal().getId(), r));
        Map<UUID, Deployment> deps = new HashMap<>();
        deployments.activeForSemester(semesterId).forEach(d -> deps.put(d.getMeal().getId(), d));
        Map<UUID, MealMetrics> mms = new HashMap<>();
        metrics.findByMealSemesterIdAndSupersededFalse(semesterId).forEach(m -> mms.put(m.getMeal().getId(), m));
        Map<UUID, List<SafetyEvent>> evs = new HashMap<>();
        events.findByMealSemesterIdAndSupersededFalseOrderByCreatedAtAsc(semesterId)
                .forEach(e -> evs.computeIfAbsent(e.getMeal().getId(), k -> new ArrayList<>()).add(e));

        List<TimelineRow> out = new ArrayList<>();
        for (MealPeriod m : meals.findBySemesterIdOrderByMealIndexAsc(semesterId)) {
            Recommendation r = recs.get(m.getId());
            Deployment d = deps.get(m.getId());
            MealMetrics mm = mms.get(m.getId());
            List<SafetyEvent> e = evs.getOrDefault(m.getId(), List.of());
            out.add(new TimelineRow(m.getId(), m.getMealIndex(), m.getMealDate(), m.getMealType(), m.getPhase(),
                    m.getState(), m.getEvalStatus(), m.isForceP0(), r == null ? null : r.getSource(),
                    d == null ? List.of() : d.getCandidate().getGeneratingLabels(),
                    d == null ? null : d.getCandidate().getD(),
                    d != null && d.getOverrideReason() != null,
                    mm == null ? null : mm.getReward(), mm == null ? null : mm.getCarbonPerAttendee(),
                    mm == null ? null : mm.getWasteGPerAttendee(), mm == null ? null : mm.getEligibleUptake(),
                    e.size(), e.stream().anyMatch(x -> "ROLLBACK".equals(x.getAction()))));
        }
        return out;
    }
}