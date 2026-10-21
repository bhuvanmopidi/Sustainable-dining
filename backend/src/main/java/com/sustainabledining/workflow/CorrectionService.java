package com.sustainabledining.workflow;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.config.ConfigView;
import com.sustainabledining.meal.*;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.workflow.WorkflowDtos.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects the observation of an already EVALUATED meal and replays the evaluation of that meal and every later
 * evaluated meal, in order. Past layout decisions are NOT re-made (those meals were really served that way); what
 * changes is how they are scored: metrics, rewards, safety events, cooldowns and the forced-P0 flag of the next
 * planned meal. Old metrics and events are kept as superseded rows, old observations as earlier revisions.
 */
@Service
@Transactional
public class CorrectionService {
    private static final Set<MealState> IN_PROGRESS = EnumSet.of(MealState.RECOMMENDED, MealState.DEPLOYED,
            MealState.OBSERVED);

    private final MealPeriodRepository meals;
    private final MealService mealService;
    private final WorkflowService workflow;
    private final ObservationRepository observations;
    private final MealMetricsRepository metrics;
    private final SafetyEventRepository events;
    private final CooldownRepository cooldowns;
    private final ReferenceModelRepository references;

    public CorrectionService(MealPeriodRepository meals, MealService mealService, WorkflowService workflow,
                             ObservationRepository observations, MealMetricsRepository metrics,
                             SafetyEventRepository events, CooldownRepository cooldowns,
                             ReferenceModelRepository references) {
        this.meals = meals;
        this.mealService = mealService;
        this.workflow = workflow;
        this.observations = observations;
        this.metrics = metrics;
        this.events = events;
        this.cooldowns = cooldowns;
        this.references = references;
    }

    public CorrectionView correct(UUID mealId, CorrectionRequest request) {
        MealPeriod meal = mealService.load(mealId);
        if (meal.getState() != MealState.EVALUATED) {
            throw ApiException.conflict("NOT_CORRECTABLE", "meal " + meal.getMealIndex() + " is " + meal.getState()
                    + "; only EVALUATED meals can be corrected");
        }
        Semester semester = meal.getSemester();
        List<MealPeriod> all = meals.findBySemesterIdOrderByMealIndexAsc(semester.getId());
        for (MealPeriod m : all) {
            if (IN_PROGRESS.contains(m.getState())) {
                throw ApiException.conflict("MEAL_IN_PROGRESS", "meal " + m.getMealIndex() + " is " + m.getState()
                        + "; finish it before correcting earlier data, so its recommendation and evaluation agree");
            }
        }
        boolean referenceFitted = references.findBySemesterId(semester.getId()).isPresent();
        if (meal.getPhase() == Phase.BASELINE && referenceFitted) {
            throw ApiException.conflict("REFERENCE_FROZEN", "baseline meal " + meal.getMealIndex()
                    + " cannot be corrected after the reference model was fitted: the reference is frozen so that "
                    + "every reward in the semester is measured against the same baseline");
        }

        int revision = observations.findFirstByMealIdOrderByRevisionDesc(mealId)
                .map(o -> o.getRevision() + 1).orElse(1);
        ObservationRequest corrected = new ObservationRequest(request.actualAttendance(), request.aggregateWasteG(),
                request.items(), request.enteredBy());
        String reason = request.reason().trim();
        observations.saveAndFlush(workflow.buildObservation(meal, revision, corrected, "MANUAL", reason));

        // Replay this meal and every later evaluated meal, oldest first (safety rules read earlier same-type meals).
        List<ReplayChange> changes = new ArrayList<>();
        int replayed = 0;
        for (MealPeriod m : all) {
            if (m.getMealIndex() < meal.getMealIndex() || m.getState() != MealState.EVALUATED) {
                continue;
            }
            Optional<MealMetrics> old = metrics.findByMealIdAndSupersededFalse(m.getId());
            List<SafetyEvent> oldEvents = events.findByMealIdAndSupersededFalse(m.getId());
            Double rewardBefore = old.map(MealMetrics::getReward).orElse(null);
            List<String> rulesBefore = rules(oldEvents);
            old.ifPresent(MealMetrics::supersede);
            oldEvents.forEach(SafetyEvent::supersede);
            metrics.flush();   // the partial unique index allows one active metrics row per meal

            WorkflowService.Evaluated ev = workflow.computeEvaluation(m);
            replayed++;
            Double rewardAfter = ev.metrics().getReward();
            List<String> rulesAfter = rules(ev.events());
            if (m.getId().equals(mealId) || !Objects.equals(rewardBefore, rewardAfter) || !rulesBefore.equals(rulesAfter)) {
                changes.add(new ReplayChange(m.getMealIndex(), m.getMealType(), rewardBefore, rewardAfter,
                        rulesBefore, rulesAfter));
            }
        }

        List<Integer> forced = rebuildSafetyState(semester, all);
        if (!referenceFitted && baselineComplete(all)) {
            referenceFitted = workflow.tryFitReference(semester);
        }
        List<CooldownView> active = cooldowns.findBySemesterIdAndRemainingOccurrencesGreaterThan(semester.getId(), 0)
                .stream().map(c -> new CooldownView(c.getPolicyLabel(), c.getMealType(), c.getRemainingOccurrences()))
                .toList();
        return new CorrectionView(mealId, meal.getMealIndex(), revision, reason, replayed, changes, active, forced,
                referenceFitted);
    }

    @Transactional(readOnly = true)
    public List<ObservationView> history(UUID mealId) {
        mealService.load(mealId);
        List<Observation> list = observations.findByMealIdOrderByRevisionAsc(mealId);
        List<ObservationView> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Observation o = list.get(i);
            out.add(new ObservationView(o.getId(), o.getRevision(), o.getActualAttendance(), o.getAggregateWasteG(),
                    o.getSource(), o.getEnteredBy(), o.getCorrectionReason(), o.getCreatedAt(), i == list.size() - 1));
        }
        return out;
    }

    /**
     * Recomputes cooldowns from scratch by walking every evaluated meal with its (current) safety events, using
     * the same rules as WorkflowService.evaluate: a non-excluded meal first counts down the cooldowns of its meal
     * type, then its events start new ones. Then sets forced P0 on the next planned meal of each type.
     * Returns the indices of the planned meals that are now forced to P0.
     */
    private List<Integer> rebuildSafetyState(Semester semester, List<MealPeriod> all) {
        int occurrences = new ConfigView(semester.getConfig().getBody()).getInt("safety.cooldown_occurrences");
        cooldowns.deleteAll(cooldowns.findBySemesterId(semester.getId()));
        cooldowns.flush();   // deletes must reach the database before inserts with the same unique key

        Map<String, Cooldown> state = new LinkedHashMap<>();
        Map<MealType, MealPeriod> lastEvaluated = new EnumMap<>(MealType.class);
        for (MealPeriod m : all) {
            if (m.getState() != MealState.EVALUATED) {
                continue;
            }
            lastEvaluated.put(m.getMealType(), m);
            if (m.getEvalStatus() == EvalStatus.EXCLUDED_ZERO_ATTENDANCE) {
                continue;
            }
            for (Cooldown c : state.values()) {
                if (c.getMealType() == m.getMealType()) {
                    c.decrement();
                }
            }
            for (SafetyEvent e : events.findByMealIdAndSupersededFalse(m.getId())) {
                for (String label : e.getCooldownLabels()) {
                    Cooldown c = state.computeIfAbsent(label + "|" + m.getMealType(),
                            k -> new Cooldown(semester, label, m.getMealType()));
                    c.reset(occurrences, e);
                }
            }
        }
        cooldowns.saveAll(state.values());

        for (MealPeriod m : all) {
            if (m.getState() == MealState.PLANNED) {
                m.setForceP0(false);
            }
        }
        List<Integer> forced = new ArrayList<>();
        for (MealPeriod last : lastEvaluated.values()) {
            boolean force = last.getEvalStatus() == EvalStatus.EXCLUDED_ZERO_ATTENDANCE
                    ? last.isForceP0()   // a skipped meal passes its rollback on
                    : events.findByMealIdAndSupersededFalse(last.getId()).stream()
                            .anyMatch(e -> "ROLLBACK".equals(e.getAction()));
            if (!force) {
                continue;
            }
            for (MealPeriod m : all) {
                if (m.getMealIndex() > last.getMealIndex() && m.getMealType() == last.getMealType()) {
                    if (m.getState() == MealState.PLANNED) {
                        m.setForceP0(true);
                        forced.add(m.getMealIndex());
                    }
                    break;
                }
            }
        }
        Collections.sort(forced);
        return forced;
    }

    private static boolean baselineComplete(List<MealPeriod> all) {
        return all.stream().filter(m -> m.getPhase() == Phase.BASELINE)
                .allMatch(m -> m.getState() == MealState.EVALUATED);
    }

    private static List<String> rules(List<SafetyEvent> list) {
        return list.stream().map(SafetyEvent::getRule).sorted().toList();
    }
}