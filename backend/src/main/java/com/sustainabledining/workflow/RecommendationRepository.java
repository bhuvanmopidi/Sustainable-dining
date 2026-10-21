package com.sustainabledining.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {
    Optional<Recommendation> findByMealIdAndSupersededFalse(UUID mealId);
    List<Recommendation> findByMealSemesterIdAndSupersededFalse(UUID semesterId);
}