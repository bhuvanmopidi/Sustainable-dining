package com.sustainabledining.meal;

/**
 * Meal lifecycle. A meal moves forward exactly one step at a time:
 * PLANNED -> RECOMMENDED -> DEPLOYED -> OBSERVED -> EVALUATED.
 * No skipping, no going back (corrections are handled by replay, which supersedes records).
 */
public enum MealState {
    PLANNED, RECOMMENDED, DEPLOYED, OBSERVED, EVALUATED;

    public boolean canMoveTo(MealState next) {
        return next.ordinal() == this.ordinal() + 1;
    }
}