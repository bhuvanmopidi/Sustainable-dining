package com.sustainabledining.catalog;

import com.sustainabledining.catalog.DishDtos.DishRequest;
import com.sustainabledining.catalog.DishDtos.DishResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dishes")
public class DishController {
    private final DishService service;

    public DishController(DishService service) {
        this.service = service;
    }

    @GetMapping
    public List<DishResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.list(includeInactive).stream().map(DishResponse::of).toList();
    }

    @GetMapping("/{id}")
    public DishResponse get(@PathVariable UUID id) {
        return DishResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DishResponse create(@Valid @RequestBody DishRequest request) {
        return DishResponse.of(service.create(request));
    }

    @PutMapping("/{id}")
    public DishResponse update(@PathVariable UUID id, @Valid @RequestBody DishRequest request) {
        return DishResponse.of(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        service.deactivate(id);
    }
}