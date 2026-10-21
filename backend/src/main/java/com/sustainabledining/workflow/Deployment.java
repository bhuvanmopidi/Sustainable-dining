package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** What was ACTUALLY put on the serving line. The learner trains on this, never on the recommendation. */
@Entity
@Table(name = "deployment")
public class Deployment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateLayout candidate;

    @Column(name = "deployed_by", nullable = false, length = 100)
    private String deployedBy;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Deployment() {}

    public Deployment(MealPeriod meal, Recommendation recommendation, CandidateLayout candidate, String deployedBy,
                      String overrideReason) {
        this.meal = meal;
        this.recommendation = recommendation;
        this.candidate = candidate;
        this.deployedBy = deployedBy;
        this.overrideReason = overrideReason;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public boolean isOverride() { return !candidate.getId().equals(recommendation.getCandidate().getId()); }

    public UUID getId() { return id; }
    public MealPeriod getMeal() { return meal; }
    public Recommendation getRecommendation() { return recommendation; }
    public CandidateLayout getCandidate() { return candidate; }
    public String getDeployedBy() { return deployedBy; }
    public String getOverrideReason() { return overrideReason; }
}