package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One DISTINCT layout the ML service generated for a meal (policies producing the same layout are merged). */
@Entity
@Table(name = "candidate_layout")
public class CandidateLayout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> ordering;

    @Column(name = "ordering_hash", nullable = false, length = 64)
    private String orderingHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generating_labels", nullable = false, columnDefinition = "jsonb")
    private List<String> generatingLabels;

    @Column(nullable = false)
    private double d;

    @Column(nullable = false)
    private double m;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Double> phi;

    @Column(name = "pred_mean")
    private Double predMean;

    private Double bonus;

    @Column(name = "ucb_score")
    private Double ucbScore;

    @Column(name = "excluded_by_cooldown", nullable = false)
    private boolean excludedByCooldown;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CandidateLayout() {}

    public CandidateLayout(MealPeriod meal, List<String> ordering, String orderingHash, List<String> generatingLabels,
                           double d, double m, List<Double> phi, Double predMean, Double bonus, Double ucbScore,
                           boolean excludedByCooldown) {
        this.meal = meal;
        this.ordering = ordering;
        this.orderingHash = orderingHash;
        this.generatingLabels = generatingLabels;
        this.d = d;
        this.m = m;
        this.phi = phi;
        this.predMean = predMean;
        this.bonus = bonus;
        this.ucbScore = ucbScore;
        this.excludedByCooldown = excludedByCooldown;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public MealPeriod getMeal() { return meal; }
    public List<String> getOrdering() { return ordering; }
    public String getOrderingHash() { return orderingHash; }
    public List<String> getGeneratingLabels() { return generatingLabels; }
    public double getD() { return d; }
    public double getM() { return m; }
    public List<Double> getPhi() { return phi; }
    public Double getPredMean() { return predMean; }
    public Double getBonus() { return bonus; }
    public Double getUcbScore() { return ucbScore; }
    public boolean isExcludedByCooldown() { return excludedByCooldown; }
    public boolean isSuperseded() { return superseded; }
}