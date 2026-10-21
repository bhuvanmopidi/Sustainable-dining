package com.sustainabledining.meal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MealPeriodRepository extends JpaRepository<MealPeriod, UUID> {
    List<MealPeriod> findBySemesterIdOrderByMealIndexAsc(UUID semesterId);
    List<MealPeriod> findBySemesterIdAndStateOrderByMealIndexAsc(UUID semesterId, MealState state);
    List<MealPeriod> findBySemesterIdAndPhaseOrderByMealIndexAsc(UUID semesterId, Phase phase);
    List<MealPeriod> findBySemesterIdAndMealIndexLessThanOrderByMealIndexAsc(UUID semesterId, int mealIndex);
    Optional<MealPeriod> findBySemesterIdAndMealIndex(UUID semesterId, int mealIndex);
    Optional<MealPeriod> findFirstBySemesterIdAndStateNotOrderByMealIndexAsc(UUID semesterId, MealState state);
    Optional<MealPeriod> findFirstBySemesterIdAndMealTypeAndMealIndexGreaterThanOrderByMealIndexAsc(
            UUID semesterId, MealType mealType, int mealIndex);
    boolean existsBySemesterIdAndMealIndexLessThanAndStateNot(UUID semesterId, int mealIndex, MealState state);
    long countBySemesterId(UUID semesterId);
    long countBySemesterIdAndPhase(UUID semesterId, Phase phase);
    long countBySemesterIdAndPhaseAndMealTypeAndMealIndexLessThan(UUID semesterId, Phase phase, MealType mealType,
                                                                    int mealIndex);
}