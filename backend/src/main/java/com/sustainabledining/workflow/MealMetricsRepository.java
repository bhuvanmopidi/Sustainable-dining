package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealMetricsRepository extends JpaRepository<MealMetrics, UUID> {
    Optional<MealMetrics> findByMealIdAndSupersededFalse(UUID mealId);
    List<MealMetrics> findByMealIdOrderByCreatedAtAsc(UUID mealId);
    List<MealMetrics> findByMealSemesterIdAndSupersededFalse(UUID semesterId);

    /** The learner log: evaluated meals before `idx` that train the learner, in deployment order. */
    @Query("select m from MealMetrics m where m.meal.semester.id = :sid and m.superseded = false "
            + "and m.learnerUpdate = true and m.meal.mealIndex < :idx order by m.meal.mealIndex asc")
    List<MealMetrics> learnerRows(@Param("sid") UUID semesterId, @Param("idx") int mealIndex);

    /** Most recent first: earlier evaluated meals of the same type that have a waste value (safety history). */
    @Query("select m from MealMetrics m where m.meal.semester.id = :sid and m.superseded = false "
            + "and m.logWaste is not null and m.meal.mealType = :type and m.meal.mealIndex < :idx "
            + "order by m.meal.mealIndex desc")
    List<MealMetrics> sameTypeHistory(@Param("sid") UUID semesterId, @Param("type") MealType type,
                                      @Param("idx") int mealIndex, Pageable page);
}