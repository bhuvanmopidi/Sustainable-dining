package com.sustainabledining.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MealDtos {
    private MealDtos() {}

    public record PlanItem(@NotNull UUID dishId, @NotNull ItemRole role, @NotNull @Min(1) Integer standardSlot) {}

    public record PlanRequest(@NotNull @Min(0) Integer expectedAttendance,
                              @NotEmpty @Valid List<PlanItem> items) {}

    public record MenuItemResponse(UUID id, UUID dishId, String dishName, String category, ItemRole role,
                                   int standardSlot, BigDecimal carbonFactorSnapshot, BigDecimal portionMassSnapshot,
                                   BigDecimal carbonScore) {}

    public record MealSummary(UUID id, int mealIndex, LocalDate date, MealType mealType, Phase phase,
                              MealState state, int expectedAttendance) {}

    public record MealDetail(UUID id, UUID semesterId, int mealIndex, LocalDate date, MealType mealType, Phase phase,
                             MealState state, EvalStatus evalStatus, boolean forceP0, int expectedAttendance,
                             List<MenuItemResponse> items) {}
}