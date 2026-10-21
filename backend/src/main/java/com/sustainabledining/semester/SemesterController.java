package com.sustainabledining.semester;

import com.sustainabledining.meal.MealDtos.MealDetail;
import com.sustainabledining.meal.MealDtos.MealSummary;
import com.sustainabledining.meal.MealService;
import com.sustainabledining.meal.MealState;
import com.sustainabledining.semester.SemesterDtos.CreateSemesterRequest;
import com.sustainabledining.semester.SemesterDtos.SemesterResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/semesters")
public class SemesterController {
    private final SemesterService semesters;
    private final MealService meals;

    public SemesterController(SemesterService semesters, MealService meals) {
        this.semesters = semesters;
        this.meals = meals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SemesterResponse create(@Valid @RequestBody CreateSemesterRequest request) {
        return semesters.create(request);
    }

    @GetMapping
    public List<SemesterResponse> list() {
        return semesters.list();
    }

    @GetMapping("/{id}")
    public SemesterResponse get(@PathVariable UUID id) {
        return semesters.get(id);
    }

    @GetMapping("/{id}/meals")
    public List<MealSummary> meals(@PathVariable UUID id, @RequestParam(required = false) MealState state) {
        return meals.list(id, state);
    }

    /** The meal the manager should work on now: the earliest meal that is not yet EVALUATED. */
    @GetMapping("/{id}/meals/next")
    public MealDetail next(@PathVariable UUID id) {
        return meals.next(id);
    }
}