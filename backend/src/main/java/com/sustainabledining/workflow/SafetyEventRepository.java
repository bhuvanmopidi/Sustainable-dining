package com.sustainabledining.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyEventRepository extends JpaRepository<SafetyEvent, UUID> {
    List<SafetyEvent> findByMealSemesterIdAndSupersededFalseOrderByCreatedAtAsc(UUID semesterId);
    List<SafetyEvent> findByMealSemesterIdAndSupersededFalseAndAcknowledgedAtIsNullOrderByCreatedAtAsc(UUID semesterId);
    List<SafetyEvent> findByMealIdAndSupersededFalse(UUID mealId);
}