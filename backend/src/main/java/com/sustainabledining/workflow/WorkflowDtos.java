package com.sustainabledining.workflow;

import com.sustainabledining.meal.EvalStatus;
import com.sustainabledining.meal.MealState;
import com.sustainabledining.meal.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkflowDtos {
    private WorkflowDtos() {}

    /** candidateId null = accept the recommendation. A different candidate needs an overrideReason. */
    public record DeployRequest(UUID candidateId, @NotBlank @Size(max = 100) String deployedBy, String overrideReason) {}

    public record ObservedItem(@NotNull UUID menuItemId, @NotNull @Min(0) Integer portionsTaken,
                               @DecimalMin("0.0") BigDecimal wasteG) {}

    public record ObservationRequest(@NotNull @Min(0) Integer actualAttendance,
                                     @NotNull @DecimalMin("0.0") BigDecimal aggregateWasteG,
                                     @NotEmpty @Valid List<ObservedItem> items,
                                     @NotBlank @Size(max = 100) String enteredBy) {}

    public record AcknowledgeRequest(@NotBlank @Size(max = 100) String by) {}

    public record CandidateView(UUID id, List<String> dishOrder, List<String> labels, double d, double m,
                                Double predMean, Double bonus, Double ucbScore, boolean excludedByCooldown,
                                boolean recommended) {}

    public record RecommendationView(UUID mealId, int mealIndex, MealState state, String source, String reasonCode,
                                     UUID recommendedCandidateId, int learnerLogLength,
                                     List<CandidateView> candidates) {}

    public record DeploymentView(UUID mealId, int mealIndex, MealState state, UUID candidateId, List<String> labels,
                                 boolean override, String overrideReason) {}

    public record SafetyEventView(UUID id, int mealIndex, String rule, double observedValue, double threshold,
                                  String action, List<String> cooldownLabels, String acknowledgedBy) {}

    public record EvaluationView(UUID mealId, int mealIndex, MealState state, EvalStatus evalStatus,
                                 List<String> deployedLabels, Double reward, Double carbonPerAttendee,
                                 Double wasteGPerAttendee, Double eligibleUptake, boolean learnerUpdate,
                                 List<SafetyEventView> safetyEvents, boolean referenceFitted) {}

    public record ReferenceView(UUID id, String version) {}

    /** A corrected observation for an EVALUATED meal. The reason is stored with the new revision. */
    public record CorrectionRequest(@NotNull @Min(0) Integer actualAttendance,
                                    @NotNull @DecimalMin("0.0") BigDecimal aggregateWasteG,
                                    @NotEmpty @Valid List<ObservedItem> items,
                                    @NotBlank @Size(max = 100) String enteredBy,
                                    @NotBlank @Size(max = 500) String reason) {}

    /** One re-evaluated meal whose reward or safety events changed. */
    public record ReplayChange(int mealIndex, MealType mealType, Double rewardBefore, Double rewardAfter,
                               List<String> safetyRulesBefore, List<String> safetyRulesAfter) {}

    public record CooldownView(String policyLabel, MealType mealType, int remainingOccurrences) {}

    public record CorrectionView(UUID mealId, int mealIndex, int revision, String reason, int replayedMeals,
                                 List<ReplayChange> changes, List<CooldownView> activeCooldowns,
                                 List<Integer> forcedP0Meals, boolean referenceFitted) {}

    public record ObservationView(UUID id, int revision, int actualAttendance, BigDecimal aggregateWasteG,
                                  String source, String enteredBy, String correctionReason, OffsetDateTime createdAt,
                                  boolean current) {}
}