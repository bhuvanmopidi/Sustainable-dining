package com.sustainabledining.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForecastRecordRepository extends JpaRepository<ForecastRecord, UUID> {
    List<ForecastRecord> findByMealId(UUID mealId);
}