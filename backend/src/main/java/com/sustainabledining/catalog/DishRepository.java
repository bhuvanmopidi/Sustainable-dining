package com.sustainabledining.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishRepository extends JpaRepository<Dish, UUID> {
    List<Dish> findAllByOrderByNameAsc();
    List<Dish> findByActiveTrueOrderByNameAsc();
    Optional<Dish> findByNameIgnoreCase(String name);
}