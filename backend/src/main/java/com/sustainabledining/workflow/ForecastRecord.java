package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import com.sustainabledining.meal.MenuItem;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Neutral-placement demand forecast for one menu item (planning output and context feature 8). */
@Entity
@Table(name = "forecast")
public class ForecastRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(name = "predicted_per_100", nullable = false)
    private double predictedPer100;

    @Column(name = "model_version", nullable = false, length = 40)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ForecastRecord() {}

    public ForecastRecord(MealPeriod meal, MenuItem menuItem, double predictedPer100, String modelVersion) {
        this.meal = meal;
        this.menuItem = menuItem;
        this.predictedPer100 = predictedPer100;
        this.modelVersion = modelVersion;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public double getPredictedPer100() { return predictedPer100; }
}