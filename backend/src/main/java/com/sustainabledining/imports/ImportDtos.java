package com.sustainabledining.imports;

import com.sustainabledining.meal.MealState;
import com.sustainabledining.meal.MealType;
import java.time.LocalDate;
import java.util.List;

public final class ImportDtos {
    private ImportDtos() {}

    /** What the import did (or, in a dry run, would do) to one meal. */
    public record ImportedMeal(int mealIndex, LocalDate date, MealType mealType, String action, MealState state,
                               Double reward, int safetyEvents) {}

    public record ImportResult(String kind, boolean dryRun, int rowsRead, int mealsAffected, List<ImportedMeal> meals) {}
}