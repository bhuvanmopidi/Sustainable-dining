package com.sustainabledining.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateLayoutRepository extends JpaRepository<CandidateLayout, UUID> {
    List<CandidateLayout> findByMealIdAndSupersededFalse(UUID mealId);
}