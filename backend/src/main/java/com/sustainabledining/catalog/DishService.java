package com.sustainabledining.catalog;

import com.sustainabledining.catalog.DishDtos.DishRequest;
import com.sustainabledining.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DishService {
    private final DishRepository repository;

    public DishService(DishRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Dish> list(boolean includeInactive) {
        return includeInactive ? repository.findAllByOrderByNameAsc() : repository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Dish get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("dish", id));
    }

    public Dish create(DishRequest r) {
        String name = r.name().trim();
        ensureNameFree(name, null);
        return repository.save(new Dish(name, r.category().trim(), r.carbonKgPerServing(), r.portionMassG(),
                r.eligibleByDefault()));
    }

    /** Editing a dish never changes past meals: menus keep their own carbon/mass snapshots. */
    public Dish update(UUID id, DishRequest r) {
        Dish dish = get(id);
        String name = r.name().trim();
        ensureNameFree(name, id);
        dish.update(name, r.category().trim(), r.carbonKgPerServing(), r.portionMassG(), r.eligibleByDefault());
        return dish;
    }

    /** Soft delete: dishes referenced by past menus must stay in the database. */
    public void deactivate(UUID id) {
        get(id).deactivate();
    }

    private void ensureNameFree(String name, UUID self) {
        repository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(self))
                .ifPresent(existing -> { throw ApiException.conflict("DUPLICATE_NAME", "a dish named '" + name + "' already exists"); });
    }
}