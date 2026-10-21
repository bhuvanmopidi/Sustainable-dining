package com.sustainabledining.workflow;

import com.sustainabledining.common.ApiError;
import com.sustainabledining.common.ApiException;
import com.sustainabledining.config.ConfigView;
import com.sustainabledining.integration.MlGateway;
import com.sustainabledining.meal.*;
import com.sustainabledining.semester.Semester;
import com.sustainabledining.semester.SemesterRepository;
import com.sustainabledining.semester.ServingSlotRepository;
import com.sustainabledining.workflow.WorkflowDtos.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The meal workflow: recommend -> deploy -> observe -> evaluate. Every step stores its result and moves the
 * meal exactly one state forward. All learning logic lives in the Python ML service; this class assembles the
 * contract payloads from the database (mirroring python/eval_harness/runner.py) and stores what comes back.
 */
@Service
@Transactional
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    /** Same weekly refit schedule as the harness, so demo runs reproduce harness results. */
    static final int FORECAST_REFIT_EVERY = 21;

    private final MealPeriodRepository meals;
    private final MealService mealService;
    private final SemesterRepository semesters;
    private final ServingSlotRepository slots;
    private final CandidateLayoutRepository candidates;
    private final RecommendationRepository recommendations;
    private final DeploymentRepository deployments;
    private final ObservationRepository observations;
    private final MealMetricsRepository metrics;
    private final SafetyEventRepository events;
    private final CooldownRepository cooldowns;
    private final ForecastRecordRepository forecasts;
    private final ReferenceModelRepository references;
    private final CatalogueContext catalogue;
    private final MlGateway ml;

    public WorkflowService(MealPeriodRepository meals, MealService mealService, SemesterRepository semesters,
                           ServingSlotRepository slots, CandidateLayoutRepository candidates,
                           RecommendationRepository recommendations, DeploymentRepository deployments,
                           ObservationRepository observations, MealMetricsRepository metrics,
                           SafetyEventRepository events, CooldownRepository cooldowns,
                           ForecastRecordRepository forecasts, ReferenceModelRepository references,
                           CatalogueContext catalogue, MlGateway ml) {
        this.meals = meals;
        this.mealService = mealService;
        this.semesters = semesters;
        this.slots = slots;
        this.candidates = candidates;
        this.recommendations = recommendations;
        this.deployments = deployments;
        this.observations = observations;
        this.metrics = metrics;
        this.events = events;
        this.cooldowns = cooldowns;
        this.forecasts = forecasts;
        this.references = references;
        this.catalogue = catalogue;
        this.ml = ml;
    }

    // ------------------------------------------------------------------ recommend
    public RecommendationView recommend(UUID mealId) {
        MealPeriod meal = mealService.load(mealId);
        if (meal.getState() != MealState.PLANNED) {
            throw ApiException.conflict("NOT_RECOMMENDABLE",
                    "meal " + meal.getMealIndex() + " is " + meal.getState() + "; only PLANNED meals can be recommended");
        }
        mealService.assertEarlierMealsEvaluated(meal);
        if (meal.getMenuItems().isEmpty()) {
            throw ApiException.unprocessable("MENU_REQUIRED", "plan the menu before asking for a recommendation");
        }
        Semester semester = meal.getSemester();
        ConfigView cfg = new ConfigView(semester.getConfig().getBody());
        Optional<ReferenceModelEntity> reference = references.findBySemesterId(semester.getId());
        if (meal.getPhase() != Phase.BASELINE && reference.isEmpty()) {
            throw ApiException.conflict("REFERENCE_NOT_FITTED", "the baseline reference has not been fitted yet; "
                    + "call POST /api/semesters/" + semester.getId() + "/reference/fit");
        }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("config", semester.getConfig().getBody());
        req.put("phase", meal.getPhase().name());
        req.put("meal", Payloads.meal(meal));
        req.put("menu_items", Payloads.menuItems(meal));
        req.put("slots", Payloads.slots(slots.findBySemesterIdOrderBySlotIndexAsc(semester.getId())));
        req.put("forecast", meal.getPhase() == Phase.BASELINE ? List.of() : runForecast(meal));
        req.put("learner_log", learnerLog(meal));
        req.put("exposure_history", exposureHistory(meal, cfg.getInt("features.exposure_window_meals")));
        req.put("active_cooldowns", activeCooldowns(semester));
        req.put("warmup_cursor", (int) meals.countBySemesterIdAndPhaseAndMealTypeAndMealIndexLessThan(
                semester.getId(), Phase.WARMUP, meal.getMealType(), meal.getMealIndex()));
        req.put("force_p0", meal.isForceP0());
        req.put("catalogue_median_carbon_score", catalogue.median(semester));

        Map<String, Object> resp = ml.recommend(req);
        String chosenHash = String.valueOf(resp.get("chosen_hash"));
        CandidateLayout chosen = null;
        for (Object o : (List<?>) resp.get("candidates")) {
            Map<?, ?> c = (Map<?, ?>) o;
            CandidateLayout layout = candidates.save(new CandidateLayout(meal, Payloads.strings(c.get("ordering")),
                    String.valueOf(c.get("ordering_hash")), Payloads.strings(c.get("generating_labels")),
                    Payloads.dbl(c.get("d")), Payloads.dbl(c.get("m")), Payloads.doubles(c.get("phi")),
                    Payloads.dblOrNull(c.get("pred_mean")), Payloads.dblOrNull(c.get("bonus")),
                    Payloads.dblOrNull(c.get("ucb_score")), Boolean.TRUE.equals(c.get("excluded_by_cooldown"))));
            if (layout.getOrderingHash().equals(chosenHash)) {
                chosen = layout;
            }
        }
        if (chosen == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ML_SERVICE_ERROR",
                    "the chosen layout is not among the returned candidates", List.of());
        }
        recommendations.save(new Recommendation(meal, chosen, String.valueOf(resp.get("source")),
                String.valueOf(resp.get("reason_code")), semester.getConfig(),
                reference.map(ReferenceModelEntity::getId).orElse(null),
                ((Number) resp.get("learner_log_length")).intValue()));
        meal.transitionTo(MealState.RECOMMENDED);
        return recommendationView(meal);
    }

    @Transactional(readOnly = true)
    public RecommendationView getRecommendation(UUID mealId) {
        MealPeriod meal = mealService.load(mealId);
        if (recommendations.findByMealIdAndSupersededFalse(mealId).isEmpty()) {
            throw ApiException.notFound("recommendation for meal", mealId);
        }
        return recommendationView(meal);
    }

    // ------------------------------------------------------------------ deploy
    public DeploymentView deploy(UUID mealId, DeployRequest request) {
        MealPeriod meal = mealService.load(mealId);
        if (meal.getState() != MealState.RECOMMENDED) {
            throw ApiException.conflict("NOT_DEPLOYABLE",
                    "meal " + meal.getMealIndex() + " is " + meal.getState() + "; only RECOMMENDED meals can be deployed");
        }
        Recommendation rec = recommendations.findByMealIdAndSupersededFalse(mealId)
                .orElseThrow(() -> ApiException.notFound("recommendation for meal", mealId));
        CandidateLayout candidate = rec.getCandidate();
        if (request.candidateId() != null) {
            candidate = candidates.findById(request.candidateId())
                    .filter(c -> c.getMeal().getId().equals(mealId) && !c.isSuperseded())
                    .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_CANDIDATE",
                            "candidate " + request.candidateId() + " is not a layout of this meal"));
        }
        if (candidate.isExcludedByCooldown()) {
            throw ApiException.unprocessable("CANDIDATE_IN_COOLDOWN",
                    "this layout is blocked by a safety cooldown for this meal type");
        }
        boolean override = !candidate.getId().equals(rec.getCandidate().getId());
        String reason = request.overrideReason() == null ? null : request.overrideReason().trim();
        if (override && (reason == null || reason.isEmpty())) {
            throw ApiException.unprocessable("OVERRIDE_REASON_REQUIRED",
                    "deploying a layout other than the recommendation needs an overrideReason");
        }
        Deployment deployment = deployments.save(new Deployment(meal, rec, candidate, request.deployedBy().trim(),
                override ? reason : null));
        meal.transitionTo(MealState.DEPLOYED);
        return new DeploymentView(mealId, meal.getMealIndex(), meal.getState(), candidate.getId(),
                candidate.getGeneratingLabels(), deployment.isOverride(), deployment.getOverrideReason());
    }

    @Transactional(readOnly = true)
    public List<String> deployedOrdering(UUID mealId) {
        return new ArrayList<>(deployments.findByMealIdAndSupersededFalse(mealId)
                .orElseThrow(() -> ApiException.notFound("deployment for meal", mealId))
                .getCandidate().getOrdering());
    }

    // ------------------------------------------------------------------ observe
    public void observe(UUID mealId, ObservationRequest request, String source) {
        MealPeriod meal = mealService.load(mealId);
        if (meal.getState() != MealState.DEPLOYED) {
            throw ApiException.conflict("NOT_OBSERVABLE",
                    "meal " + meal.getMealIndex() + " is " + meal.getState() + "; only DEPLOYED meals can be observed");
        }
        observations.save(buildObservation(meal, 1, request, source, null));
        meal.transitionTo(MealState.OBSERVED);
    }

    /** Validates an observation against the meal's menu and builds it (not saved). Also used by corrections. */
    public Observation buildObservation(MealPeriod meal, int revision, ObservationRequest request, String source,
                                        String correctionReason) {
        List<ApiError.Detail> errors = new ArrayList<>();
        if (request.actualAttendance() > meal.getSemester().getEnrolledStudents()) {
            errors.add(new ApiError.Detail("actualAttendance", "cannot exceed enrolled students"));
        }
        Map<UUID, MenuItem> menu = new HashMap<>();
        meal.getMenuItems().forEach(i -> menu.put(i.getId(), i));
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            ObservedItem item = request.items().get(i);
            if (!menu.containsKey(item.menuItemId())) {
                errors.add(new ApiError.Detail("items[" + i + "].menuItemId", "not an item on this meal's menu"));
            } else if (!seen.add(item.menuItemId())) {
                errors.add(new ApiError.Detail("items[" + i + "].menuItemId", "item reported twice"));
            }
        }
        for (UUID id : menu.keySet()) {
            if (!seen.contains(id)) {
                errors.add(new ApiError.Detail("items", "missing outcome for " + menu.get(id).getDish().getName()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OBSERVATION",
                    "the observation must report every menu item exactly once", errors);
        }
        Observation obs = new Observation(meal, revision, request.actualAttendance(), request.aggregateWasteG(), source,
                request.enteredBy().trim(), correctionReason);
        for (ObservedItem item : request.items()) {
            obs.addItem(menu.get(item.menuItemId()), item.portionsTaken(), item.wasteG());
        }
        return obs;
    }

    // ------------------------------------------------------------------ evaluate
    /** What one /evaluate call produced and stored for a meal. */
    public record Evaluated(Deployment deployment, MealMetrics metrics, List<SafetyEvent> events, EvalStatus status,
                            boolean forceP0Next) {}

    public EvaluationView evaluate(UUID mealId) {
        MealPeriod meal = mealService.load(mealId);
        if (meal.getState() != MealState.OBSERVED) {
            throw ApiException.conflict("NOT_EVALUABLE",
                    "meal " + meal.getMealIndex() + " is " + meal.getState() + "; only OBSERVED meals can be evaluated");
        }
        Semester semester = meal.getSemester();
        ConfigView cfg = new ConfigView(semester.getConfig().getBody());
        Evaluated ev = computeEvaluation(meal);

        // Safety bookkeeping, in the same order as the harness.
        if (ev.status() != EvalStatus.EXCLUDED_ZERO_ATTENDANCE) {
            for (Cooldown c : cooldowns.findBySemesterIdAndMealTypeAndRemainingOccurrencesGreaterThan(
                    semester.getId(), meal.getMealType(), 0)) {
                c.decrement();
            }
            int occurrences = cfg.getInt("safety.cooldown_occurrences");
            for (SafetyEvent e : ev.events()) {
                for (String label : e.getCooldownLabels()) {
                    Cooldown c = cooldowns.findBySemesterIdAndPolicyLabelAndMealType(semester.getId(), label,
                            meal.getMealType()).orElseGet(() -> new Cooldown(semester, label, meal.getMealType()));
                    c.reset(occurrences, e);
                    cooldowns.save(c);
                }
            }
            if (ev.forceP0Next()) {
                nextSameType(meal).ifPresent(n -> n.setForceP0(true));
            }
        } else if (meal.isForceP0()) {
            nextSameType(meal).ifPresent(n -> n.setForceP0(true));   // a skipped meal does not consume the rollback
        }
        meal.transitionTo(MealState.EVALUATED);

        boolean fitted = references.findBySemesterId(semester.getId()).isPresent();
        if (meal.getPhase() == Phase.BASELINE && isLastBaselineMeal(meal)) {
            fitted = tryFitReference(semester);
        }
        return evaluationView(meal, ev.deployment(), ev.metrics(), ev.events(), fitted);
    }

    /**
     * Calls /evaluate for the meal's latest observation and stores the metrics, safety events and eval status.
     * Does not touch cooldowns, forced P0 or the meal state; the caller does that. Also used to replay meals
     * after a correction, so any previous active metrics and events must already be superseded.
     */
    @SuppressWarnings("unchecked")
    public Evaluated computeEvaluation(MealPeriod meal) {
        Semester semester = meal.getSemester();
        ConfigView cfg = new ConfigView(semester.getConfig().getBody());
        Deployment dep = deployments.findByMealIdAndSupersededFalse(meal.getId()).orElseThrow();
        Observation obs = observations.findFirstByMealIdOrderByRevisionDesc(meal.getId()).orElseThrow();
        Optional<ReferenceModelEntity> reference = references.findBySemesterId(semester.getId());
        int histLen = cfg.getInt("safety.waste_sustained_window") - 1;

        Map<String, Object> deployed = new LinkedHashMap<>();
        deployed.put("ordering_hash", dep.getCandidate().getOrderingHash());
        deployed.put("generating_labels", dep.getCandidate().getGeneratingLabels());
        deployed.put("phi", dep.getCandidate().getPhi());

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("config", semester.getConfig().getBody());
        req.put("meal", Payloads.meal(meal));
        req.put("menu_items", Payloads.menuItems(meal));
        req.put("deployed_candidate", deployed);
        req.put("observation", Payloads.observation(obs));
        req.put("reference_model", reference.map(ReferenceModelEntity::getBody).orElse(null));
        req.put("same_type_history", histLen > 0 ? sameTypeHistory(meal, histLen) : List.of());

        Map<String, Object> resp = ml.evaluate(req);
        EvalStatus status = EvalStatus.valueOf(String.valueOf(resp.get("eval_status")));
        boolean learnerUpdate = Boolean.TRUE.equals(resp.get("learner_update"));
        MealMetrics mm = metrics.save(new MealMetrics(meal, obs, (Map<String, Object>) resp.get("metrics"),
                learnerUpdate, reference.map(ReferenceModelEntity::getId).orElse(null), semester.getConfig()));
        meal.setEvalStatus(status);

        List<SafetyEvent> saved = new ArrayList<>();
        for (Object o : (List<?>) resp.get("safety_events")) {
            Map<?, ?> e = (Map<?, ?>) o;
            saved.add(events.save(new SafetyEvent(meal, String.valueOf(e.get("rule")), Payloads.dbl(e.get("observed_value")),
                    Payloads.dbl(e.get("threshold")), String.valueOf(e.get("action")),
                    Payloads.strings(e.get("cooldown_labels")))));
        }
        return new Evaluated(dep, mm, saved, status, Boolean.TRUE.equals(resp.get("force_p0_next")));
    }

    /** Fits the frozen reference if it is missing. A rejected fit is logged, not thrown (retry via the endpoint). */
    public boolean tryFitReference(Semester semester) {
        try {
            doFitReference(semester);
            return true;
        } catch (ApiException e) {
            log.warn("Reference fit after the baseline failed ({}); retry with POST /api/semesters/{}/reference/fit",
                    e.getMessage(), semester.getId());
            return false;
        }
    }

    // ------------------------------------------------------------------ reference
    public ReferenceView fitReference(UUID semesterId) {
        Semester semester = semesters.findById(semesterId).orElseThrow(() -> ApiException.notFound("semester", semesterId));
        ReferenceModelEntity ref = doFitReference(semester);
        return new ReferenceView(ref.getId(), ref.getVersion());
    }

    @SuppressWarnings("unchecked")
    private ReferenceModelEntity doFitReference(Semester semester) {
        Optional<ReferenceModelEntity> existing = references.findBySemesterId(semester.getId());
        if (existing.isPresent()) {
            return existing.get();   // frozen: fitted once per semester
        }
        List<MealPeriod> baseline = meals.findBySemesterIdAndPhaseOrderByMealIndexAsc(semester.getId(), Phase.BASELINE);
        List<Map<String, Object>> baselineMeals = new ArrayList<>();
        for (MealPeriod m : baseline) {
            if (m.getState() != MealState.EVALUATED) {
                throw ApiException.conflict("BASELINE_INCOMPLETE", "baseline meal " + m.getMealIndex() + " is not EVALUATED");
            }
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("meal", Payloads.meal(m));
            bm.put("menu_items", Payloads.menuItems(m));
            bm.put("observation", Payloads.observation(observations.findFirstByMealIdOrderByRevisionDesc(m.getId()).orElseThrow()));
            baselineMeals.add(bm);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("config", semester.getConfig().getBody());
        req.put("baseline_meals", baselineMeals);
        Map<String, Object> resp = ml.fitReference(req);
        if (!"FITTED".equals(resp.get("status"))) {
            List<ApiError.Detail> details = new ArrayList<>();
            for (Object o : (List<?>) resp.getOrDefault("missing_coverage", List.of())) {
                Map<?, ?> g = (Map<?, ?>) o;
                details.add(new ApiError.Detail("dish " + g.get("dish_id"), g.get("meal_type") + ": seen "
                        + g.get("appearances") + " time(s), need " + g.get("required")));
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFERENCE_FIT_REJECTED",
                    "every dish must appear enough times per meal type during the baseline", details);
        }
        Map<String, Object> body = (Map<String, Object>) resp.get("reference_model");
        ReferenceModelEntity ref = references.save(new ReferenceModelEntity(semester, String.valueOf(body.get("version")), body));
        semester.setReferenceModelId(ref.getId());
        return ref;
    }

    // ------------------------------------------------------------------ safety events
    @Transactional(readOnly = true)
    public List<SafetyEventView> safetyEvents(UUID semesterId, boolean unacknowledgedOnly) {
        List<SafetyEvent> list = unacknowledgedOnly
                ? events.findByMealSemesterIdAndSupersededFalseAndAcknowledgedAtIsNullOrderByCreatedAtAsc(semesterId)
                : events.findByMealSemesterIdAndSupersededFalseOrderByCreatedAtAsc(semesterId);
        return list.stream().map(WorkflowService::eventView).toList();
    }

    public SafetyEventView acknowledge(UUID eventId, String by) {
        SafetyEvent e = events.findById(eventId).orElseThrow(() -> ApiException.notFound("safety event", eventId));
        e.acknowledge(by.trim());
        return eventView(e);
    }

    // ------------------------------------------------------------------ payload helpers (mirror the harness)
    private List<Map<String, Object>> runForecast(MealPeriod meal) {
        Semester semester = meal.getSemester();
        int baselineCount = (int) meals.countBySemesterIdAndPhase(semester.getId(), Phase.BASELINE);
        int t = meal.getMealIndex();
        int fitPoint = baselineCount + FORECAST_REFIT_EVERY * ((t - baselineCount) / FORECAST_REFIT_EVERY);

        List<Map<String, Object>> history = new ArrayList<>();
        for (MealPeriod m : meals.findBySemesterIdAndMealIndexLessThanOrderByMealIndexAsc(semester.getId(), fitPoint)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("meal", Payloads.meal(m));
            h.put("menu_items", Payloads.menuItems(m));
            h.put("deployed_ordering", deployments.findByMealIdAndSupersededFalse(m.getId()).orElseThrow()
                    .getCandidate().getOrdering());
            h.put("observation", Payloads.observation(observations.findFirstByMealIdOrderByRevisionDesc(m.getId()).orElseThrow()));
            history.add(h);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", "RANDOM_FOREST");
        req.put("history", history);
        req.put("target_meal", Payloads.meal(meal));
        req.put("target_menu_items", Payloads.menuItems(meal));
        req.put("random_seed", semester.getSimSeed() == null ? 0 : semester.getSimSeed());
        Map<String, Object> resp = ml.forecast(req);

        Map<UUID, MenuItem> menu = new HashMap<>();
        meal.getMenuItems().forEach(i -> menu.put(i.getId(), i));
        List<Map<String, Object>> predictions = new ArrayList<>();
        for (Object o : (List<?>) resp.get("predictions")) {
            Map<?, ?> p = (Map<?, ?>) o;
            UUID itemId = UUID.fromString(String.valueOf(p.get("menu_item_id")));
            double value = Payloads.dbl(p.get("predicted_per_100"));
            if (menu.containsKey(itemId)) {
                forecasts.save(new ForecastRecord(meal, menu.get(itemId), value, String.valueOf(resp.get("model_version"))));
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("menu_item_id", itemId.toString());
            f.put("predicted_per_100", value);
            predictions.add(f);
        }
        return predictions;
    }

    private List<Map<String, Object>> learnerLog(MealPeriod meal) {
        List<Map<String, Object>> log = new ArrayList<>();
        for (MealMetrics row : metrics.learnerRows(meal.getSemester().getId(), meal.getMealIndex())) {
            CandidateLayout c = deployments.findByMealIdAndSupersededFalse(row.getMeal().getId()).orElseThrow().getCandidate();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("meal_id", row.getMeal().getId().toString());
            entry.put("phi", c.getPhi());
            entry.put("reward", row.getReward());
            entry.put("deployed_labels", c.getGeneratingLabels());
            log.add(entry);
        }
        return log;
    }

    private List<Double> exposureHistory(MealPeriod meal, int window) {
        List<Double> ds = new ArrayList<>();
        for (Deployment d : deployments.findEarlier(meal.getSemester().getId(), meal.getMealIndex(), PageRequest.of(0, window))) {
            ds.add(d.getCandidate().getD());
        }
        Collections.reverse(ds);   // oldest first, most recent last
        return ds;
    }

    private List<Map<String, Object>> activeCooldowns(Semester semester) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Cooldown c : cooldowns.findBySemesterIdAndRemainingOccurrencesGreaterThan(semester.getId(), 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policy_label", c.getPolicyLabel());
            m.put("meal_type", c.getMealType().name());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> sameTypeHistory(MealPeriod meal, int histLen) {
        List<MealMetrics> rows = new ArrayList<>(metrics.sameTypeHistory(meal.getSemester().getId(), meal.getMealType(),
                meal.getMealIndex(), PageRequest.of(0, histLen)));
        Collections.reverse(rows);   // oldest first
        List<Map<String, Object>> out = new ArrayList<>();
        for (MealMetrics r : rows) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("meal_id", r.getMeal().getId().toString());
            h.put("log_waste", r.getLogWaste());
            h.put("eligible_uptake", r.getEligibleUptake());
            h.put("uptake_ref", r.getUptakeRef());
            h.put("deployed_labels", deployments.findByMealIdAndSupersededFalse(r.getMeal().getId()).orElseThrow()
                    .getCandidate().getGeneratingLabels());
            out.add(h);
        }
        return out;
    }

    private Optional<MealPeriod> nextSameType(MealPeriod meal) {
        return meals.findFirstBySemesterIdAndMealTypeAndMealIndexGreaterThanOrderByMealIndexAsc(
                meal.getSemester().getId(), meal.getMealType(), meal.getMealIndex());
    }

    private boolean isLastBaselineMeal(MealPeriod meal) {
        return meals.findBySemesterIdAndMealIndex(meal.getSemester().getId(), meal.getMealIndex() + 1)
                .map(n -> n.getPhase() != Phase.BASELINE).orElse(true);
    }

    // ------------------------------------------------------------------ views
    private RecommendationView recommendationView(MealPeriod meal) {
        Recommendation rec = recommendations.findByMealIdAndSupersededFalse(meal.getId()).orElseThrow();
        Map<String, String> names = new HashMap<>();
        meal.getMenuItems().forEach(i -> names.put(i.getId().toString(), i.getDish().getName()));
        List<CandidateView> views = candidates.findByMealIdAndSupersededFalse(meal.getId()).stream()
                .map(c -> new CandidateView(c.getId(), c.getOrdering().stream().map(id -> names.getOrDefault(id, id)).toList(),
                        c.getGeneratingLabels(), c.getD(), c.getM(), c.getPredMean(), c.getBonus(), c.getUcbScore(),
                        c.isExcludedByCooldown(), c.getId().equals(rec.getCandidate().getId())))
                .toList();
        return new RecommendationView(meal.getId(), meal.getMealIndex(), meal.getState(), rec.getSource(),
                rec.getReasonCode(), rec.getCandidate().getId(), rec.getLearnerLogLength(), views);
    }

    private static SafetyEventView eventView(SafetyEvent e) {
        return new SafetyEventView(e.getId(), e.getMeal().getMealIndex(), e.getRule(), e.getObservedValue(),
                e.getThreshold(), e.getAction(), e.getCooldownLabels(), e.getAcknowledgedBy());
    }

    private static EvaluationView evaluationView(MealPeriod meal, Deployment dep, MealMetrics mm, List<SafetyEvent> saved,
                                                 boolean fitted) {
        return new EvaluationView(meal.getId(), meal.getMealIndex(), meal.getState(), meal.getEvalStatus(),
                dep.getCandidate().getGeneratingLabels(), mm.getReward(), mm.getCarbonPerAttendee(),
                mm.getWasteGPerAttendee(), mm.getEligibleUptake(), mm.isLearnerUpdate(),
                saved.stream().map(WorkflowService::eventView).toList(), fitted);
    }
}