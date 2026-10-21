package com.sustainabledining.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationRepository extends JpaRepository<Observation, UUID> {
    Optional<Observation> findFirstByMealIdOrderByRevisionDesc(UUID mealId);
    List<Observation> findByMealIdOrderByRevisionAsc(UUID mealId);
}