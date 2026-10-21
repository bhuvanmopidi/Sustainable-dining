package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CooldownRepository extends JpaRepository<Cooldown, UUID> {
    List<Cooldown> findBySemesterId(UUID semesterId);
    List<Cooldown> findBySemesterIdAndRemainingOccurrencesGreaterThan(UUID semesterId, int remaining);
    List<Cooldown> findBySemesterIdAndMealTypeAndRemainingOccurrencesGreaterThan(UUID semesterId, MealType mealType,
                                                                                 int remaining);
    Optional<Cooldown> findBySemesterIdAndPolicyLabelAndMealType(UUID semesterId, String policyLabel, MealType mealType);
}