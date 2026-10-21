package com.sustainabledining.workflow;

import com.sustainabledining.config.ExperimentConfig;
import com.sustainabledining.meal.MealPeriod;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Metrics and reward computed by the ML service's /evaluate. Derived data: never edited by hand. */
@Entity
@Table(name = "meal_metrics")
public class MealMetrics {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_id", nullable = false)
    private Observation observation;

    @Column(name = "carbon_per_attendee") private Double carbonPerAttendee;
    @Column(name = "waste_g_per_attendee") private Double wasteGPerAttendee;
    @Column(name = "log_waste") private Double logWaste;
    @Column(name = "eligible_uptake") private Double eligibleUptake;
    @Column(name = "total_portions_per_attendee") private Double totalPortionsPerAttendee;
    @Column(name = "c_ref") private Double cRef;
    @Column(name = "w_ref") private Double wRef;
    @Column(name = "uptake_ref") private Double uptakeRef;
    @Column(name = "z_c") private Double zC;
    @Column(name = "z_w") private Double zW;
    @Column(name = "reward_unclipped") private Double rewardUnclipped;
    @Column(name = "reward") private Double reward;

    @Column(name = "learner_update", nullable = false)
    private boolean learnerUpdate;

    @Column(name = "reference_model_id")
    private UUID referenceModelId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_id", nullable = false)
    private ExperimentConfig config;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MealMetrics() {}

    public MealMetrics(MealPeriod meal, Observation observation, Map<String, Object> m, boolean learnerUpdate,
                       UUID referenceModelId, ExperimentConfig config) {
        this.meal = meal;
        this.observation = observation;
        this.carbonPerAttendee = Payloads.dblOrNull(m.get("carbon_per_attendee"));
        this.wasteGPerAttendee = Payloads.dblOrNull(m.get("waste_g_per_attendee"));
        this.logWaste = Payloads.dblOrNull(m.get("log_waste"));
        this.eligibleUptake = Payloads.dblOrNull(m.get("eligible_uptake"));
        this.totalPortionsPerAttendee = Payloads.dblOrNull(m.get("total_portions_per_attendee"));
        this.cRef = Payloads.dblOrNull(m.get("c_ref"));
        this.wRef = Payloads.dblOrNull(m.get("w_ref"));
        this.uptakeRef = Payloads.dblOrNull(m.get("uptake_ref"));
        this.zC = Payloads.dblOrNull(m.get("z_c"));
        this.zW = Payloads.dblOrNull(m.get("z_w"));
        this.rewardUnclipped = Payloads.dblOrNull(m.get("reward_unclipped"));
        this.reward = Payloads.dblOrNull(m.get("reward"));
        this.learnerUpdate = learnerUpdate;
        this.referenceModelId = referenceModelId;
        this.config = config;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    /** Replaced by a re-evaluation after a correction. The row is kept for the audit trail. */
    public void supersede() { this.superseded = true; }

    public UUID getId() { return id; }
    public boolean isSuperseded() { return superseded; }
    public Observation getObservation() { return observation; }
    public MealPeriod getMeal() { return meal; }
    public Double getCarbonPerAttendee() { return carbonPerAttendee; }
    public Double getWasteGPerAttendee() { return wasteGPerAttendee; }
    public Double getLogWaste() { return logWaste; }
    public Double getEligibleUptake() { return eligibleUptake; }
    public Double getUptakeRef() { return uptakeRef; }
    public Double getReward() { return reward; }
    public boolean isLearnerUpdate() { return learnerUpdate; }
}