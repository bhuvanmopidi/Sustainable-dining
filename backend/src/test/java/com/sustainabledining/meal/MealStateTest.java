package com.sustainabledining.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sustainabledining.common.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MealStateTest {
    @Test
    void eachForwardStepIsAllowed() {
        MealState[] s = MealState.values();
        for (int i = 0; i + 1 < s.length; i++) {
            assertThat(s[i].canMoveTo(s[i + 1])).as(s[i] + " -> " + s[i + 1]).isTrue();
        }
    }

    @Test
    void skippingAStepIsRejected() {
        assertThat(MealState.PLANNED.canMoveTo(MealState.DEPLOYED)).isFalse();
        assertThat(MealState.RECOMMENDED.canMoveTo(MealState.EVALUATED)).isFalse();
    }

    @Test
    void goingBackIsRejected() {
        assertThat(MealState.DEPLOYED.canMoveTo(MealState.RECOMMENDED)).isFalse();
        assertThat(MealState.EVALUATED.canMoveTo(MealState.PLANNED)).isFalse();
    }

    @Test
    void evaluatedIsTerminal() {
        for (MealState next : MealState.values()) {
            assertThat(MealState.EVALUATED.canMoveTo(next)).isFalse();
        }
    }

    @Test
    void entityEnforcesTheStateMachine() {
        MealPeriod meal = new MealPeriod(null, 0, LocalDate.of(2026, 7, 6), MealType.BREAKFAST, Phase.BASELINE);
        meal.transitionTo(MealState.RECOMMENDED);
        assertThat(meal.getState()).isEqualTo(MealState.RECOMMENDED);
        assertThatThrownBy(() -> meal.transitionTo(MealState.OBSERVED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot move from RECOMMENDED to OBSERVED");
    }
}