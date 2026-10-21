package com.sustainabledining.catalog;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Request/response shapes for the dish API. The backend's own API uses camelCase JSON;
 *  only the Java-to-Python contracts use snake_case. */
public final class DishDtos {
    private DishDtos() {}

    public record DishRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String category,
            @NotNull @DecimalMin("0.0") @Digits(integer = 4, fraction = 4) BigDecimal carbonKgPerServing,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal portionMassG,
            @NotNull Boolean eligibleByDefault) {}

    public record DishResponse(UUID id, String name, String category, BigDecimal carbonKgPerServing,
                               BigDecimal portionMassG, boolean eligibleByDefault, boolean active) {
        static DishResponse of(Dish d) {
            return new DishResponse(d.getId(), d.getName(), d.getCategory(), d.getCarbonKgPerServing(),
                    d.getPortionMassG(), d.isEligibleByDefault(), d.isActive());
        }
    }
}