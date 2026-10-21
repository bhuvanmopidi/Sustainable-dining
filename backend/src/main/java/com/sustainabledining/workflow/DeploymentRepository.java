package com.sustainabledining.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {
    Optional<Deployment> findByMealIdAndSupersededFalse(UUID mealId);

    /** Active deployments of a whole semester, with their layouts (for the timeline). */
    @Query("select d from Deployment d join fetch d.candidate where d.meal.semester.id = :sid and d.superseded = false")
    List<Deployment> activeForSemester(@Param("sid") UUID semesterId);

    /** Most recent first: deployments of meals before `idx` (used for recent nudge exposure). */
    @Query("select d from Deployment d where d.meal.semester.id = :sid and d.superseded = false "
            + "and d.meal.mealIndex < :idx order by d.meal.mealIndex desc")
    List<Deployment> findEarlier(@Param("sid") UUID semesterId, @Param("idx") int mealIndex, Pageable page);
}