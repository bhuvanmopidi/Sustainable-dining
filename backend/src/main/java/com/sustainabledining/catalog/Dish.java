package com.sustainabledining.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dish")
public class Dish {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "carbon_kg_per_serving", nullable = false, precision = 8, scale = 4)
    private BigDecimal carbonKgPerServing;

    @Column(name = "portion_mass_g", nullable = false, precision = 8, scale = 2)
    private BigDecimal portionMassG;

    @Column(name = "eligible_by_default", nullable = false)
    private boolean eligibleByDefault;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Dish() {}

    public Dish(String name, String category, BigDecimal carbonKgPerServing, BigDecimal portionMassG,
                boolean eligibleByDefault) {
        update(name, category, carbonKgPerServing, portionMassG, eligibleByDefault);
    }

    public void update(String name, String category, BigDecimal carbonKgPerServing, BigDecimal portionMassG,
                       boolean eligibleByDefault) {
        this.name = name;
        this.category = category;
        this.carbonKgPerServing = carbonKgPerServing;
        this.portionMassG = portionMassG;
        this.eligibleByDefault = eligibleByDefault;
    }

    public void deactivate() { this.active = false; }

    @PrePersist
    void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public BigDecimal getCarbonKgPerServing() { return carbonKgPerServing; }
    public BigDecimal getPortionMassG() { return portionMassG; }
    public boolean isEligibleByDefault() { return eligibleByDefault; }
    public boolean isActive() { return active; }
}