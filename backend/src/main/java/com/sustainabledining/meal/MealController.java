package com.sustainabledining.meal;

import com.sustainabledining.meal.MealDtos.MealDetail;
import com.sustainabledining.meal.MealDtos.PlanRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meals")
public class MealController {
    private final MealService service;

    public MealController(MealService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public MealDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Set (or replace) the menu and expected attendance of a PLANNED meal. */
    @PutMapping("/{id}/plan")
    public MealDetail plan(@PathVariable UUID id, @Valid @RequestBody PlanRequest request) {
        return service.plan(id, request);
    }
}