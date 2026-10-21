package com.sustainabledining.workflow;

import com.sustainabledining.config.ExperimentConfig;
import com.sustainabledining.meal.MealPeriod;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recommendation")
public class Recommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateLayout candidate;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_id", nullable = false)
    private ExperimentConfig config;

    @Column(name = "reference_model_id")
    private UUID referenceModelId;

    @Column(name = "learner_log_length", nullable = false)
    private int learnerLogLength;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Recommendation() {}

    public Recommendation(MealPeriod meal, CandidateLayout candidate, String source, String reasonCode,
                          ExperimentConfig config, UUID referenceModelId, int learnerLogLength) {
        this.meal = meal;
        this.candidate = candidate;
        this.source = source;
        this.reasonCode = reasonCode;
        this.config = config;
        this.referenceModelId = referenceModelId;
        this.learnerLogLength = learnerLogLength;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public MealPeriod getMeal() { return meal; }
    public CandidateLayout getCandidate() { return candidate; }
    public String getSource() { return source; }
    public String getReasonCode() { return reasonCode; }
    public int getLearnerLogLength() { return learnerLogLength; }
}