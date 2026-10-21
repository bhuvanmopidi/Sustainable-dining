package com.sustainabledining.semester;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.config.ConfigView;
import com.sustainabledining.config.ExperimentConfig;
import com.sustainabledining.config.ExperimentConfigRepository;
import com.sustainabledining.meal.MealPeriod;
import com.sustainabledining.meal.MealPeriodRepository;
import com.sustainabledining.meal.MealType;
import com.sustainabledining.meal.Phase;
import com.sustainabledining.semester.SemesterDtos.CreateSemesterRequest;
import com.sustainabledining.semester.SemesterDtos.SemesterResponse;
import com.sustainabledining.semester.SemesterDtos.SlotResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SemesterService {
    public static final Set<String> SCENARIOS = Set.of("NO_PLACEMENT_EFFECT", "SUCCESSFUL_NUDGING",
            "FAILED_SUBSTITUTION", "NUDGE_FATIGUE", "CHANGING_PREFERENCES", "CONTEXT_DEPENDENT", "AVOIDANCE_STRESS");
    private static final BigDecimal FIXED_SLOT_VISIBILITY = new BigDecimal("0.500");

    private final SemesterRepository semesters;
    private final ServingSlotRepository slots;
    private final MealPeriodRepository meals;
    private final ExperimentConfigRepository configs;

    public SemesterService(SemesterRepository semesters, ServingSlotRepository slots, MealPeriodRepository meals,
                           ExperimentConfigRepository configs) {
        this.semesters = semesters;
        this.slots = slots;
        this.meals = meals;
        this.configs = configs;
    }

    /** Creates the semester, its serving line and the whole meal schedule (all meals start PLANNED). */
    public SemesterResponse create(CreateSemesterRequest r) {
        validateMode(r);
        String version = r.configVersion() == null ? "v1" : r.configVersion();
        ExperimentConfig config = configs.findByVersion(version)
                .orElseThrow(() -> ApiException.notFound("config", version));
        ConfigView cfg = new ConfigView(config.getBody());

        Semester semester = semesters.save(new Semester(r.name().trim(), r.mode(), r.startDate(),
                r.enrolledStudents(), config, r.simSeed(), r.simScenario()));
        createSlots(semester, cfg, r.eligibleSlots() == null ? 3 : r.eligibleSlots(),
                r.fixedSlots() == null ? 2 : r.fixedSlots());
        createMeals(semester, cfg);
        return toResponse(semester);
    }

    @Transactional(readOnly = true)
    public List<SemesterResponse> list() {
        return semesters.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SemesterResponse get(UUID id) {
        return toResponse(load(id));
    }

    @Transactional(readOnly = true)
    public Semester load(UUID id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("semester", id));
    }

    private void validateMode(CreateSemesterRequest r) {
        boolean hasSim = r.simSeed() != null || r.simScenario() != null;
        if (r.mode() == SemesterMode.LIVE && hasSim) {
            throw ApiException.unprocessable("INVALID_MODE", "LIVE semesters cannot have simSeed or simScenario");
        }
        if (r.mode() == SemesterMode.SIMULATED_DEMO) {
            if (r.simSeed() == null || r.simScenario() == null) {
                throw ApiException.unprocessable("INVALID_MODE", "SIMULATED_DEMO semesters need simSeed and simScenario");
            }
            if (!SCENARIOS.contains(r.simScenario())) {
                throw ApiException.unprocessable("INVALID_MODE", "unknown simScenario '" + r.simScenario() + "'");
            }
        }
    }

    /** Eligible slots get evenly spaced visibility from max down to min; fixed slots get 0.5. */
    private void createSlots(Semester semester, ConfigView cfg, int eligible, int fixed) {
        double vmax = cfg.getDouble("layout.eligible_slot_visibility_max");
        double vmin = cfg.getDouble("layout.eligible_slot_visibility_min");
        List<ServingSlot> list = new ArrayList<>();
        for (int k = 0; k < eligible; k++) {
            double v = eligible == 1 ? vmax : vmax - (vmax - vmin) * k / (eligible - 1);
            list.add(new ServingSlot(semester, k + 1, BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP), true));
        }
        for (int k = 0; k < fixed; k++) {
            list.add(new ServingSlot(semester, eligible + k + 1, FIXED_SLOT_VISIBILITY, false));
        }
        slots.saveAll(list);
    }

    /** Same indexing as the Python harness: meal t is day t/3, meal type t%3; phase follows the config weeks. */
    private void createMeals(Semester semester, ConfigView cfg) {
        int weeks = cfg.getInt("semester.weeks");
        int days = cfg.getInt("semester.days_per_week");
        List<String> types = cfg.getStringList("semester.meal_types");
        int baselineWeeks = cfg.getInt("semester.baseline_weeks");
        int warmupWeeks = cfg.getInt("semester.warmup_weeks");
        int perWeek = days * types.size();

        List<MealPeriod> list = new ArrayList<>();
        for (int t = 0; t < weeks * perWeek; t++) {
            int week = t / perWeek;
            Phase phase = week < baselineWeeks ? Phase.BASELINE
                    : week < baselineWeeks + warmupWeeks ? Phase.WARMUP : Phase.ADAPTIVE;
            list.add(new MealPeriod(semester, t, semester.getStartDate().plusDays(t / types.size()),
                    MealType.valueOf(types.get(t % types.size())), phase));
        }
        meals.saveAll(list);
    }

    private SemesterResponse toResponse(Semester s) {
        List<SlotResponse> slotList = slots.findBySemesterIdOrderBySlotIndexAsc(s.getId()).stream()
                .map(x -> new SlotResponse(x.getSlotIndex(), x.getVisibility(), x.isEligibleSlot()))
                .toList();
        Map<Phase, Long> byPhase = new EnumMap<>(Phase.class);
        for (Phase p : Phase.values()) {
            byPhase.put(p, meals.countBySemesterIdAndPhase(s.getId(), p));
        }
        return new SemesterResponse(s.getId(), s.getName(), s.getMode(), s.getStartDate(), s.getEnrolledStudents(),
                s.getConfig().getVersion(), s.getSimSeed(), s.getSimScenario(), s.getReferenceModelId() != null,
                meals.countBySemesterId(s.getId()), byPhase, slotList);
    }
}