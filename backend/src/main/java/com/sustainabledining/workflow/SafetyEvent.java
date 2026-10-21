package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "safety_event")
public class SafetyEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @Column(nullable = false, length = 20)
    private String rule;

    @Column(name = "observed_value", nullable = false)
    private double observedValue;

    @Column(nullable = false)
    private double threshold;

    @Column(nullable = false, length = 12)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cooldown_labels", nullable = false, columnDefinition = "jsonb")
    private List<String> cooldownLabels;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SafetyEvent() {}

    public SafetyEvent(MealPeriod meal, String rule, double observedValue, double threshold, String action,
                       List<String> cooldownLabels) {
        this.meal = meal;
        this.rule = rule;
        this.observedValue = observedValue;
        this.threshold = threshold;
        this.action = action;
        this.cooldownLabels = cooldownLabels;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public void acknowledge(String by) {
        this.acknowledgedBy = by;
        this.acknowledgedAt = OffsetDateTime.now();
    }

    /** Replaced by a re-evaluation after a correction. The row is kept for the audit trail. */
    public void supersede() { this.superseded = true; }

    public UUID getId() { return id; }
    public boolean isSuperseded() { return superseded; }
    public MealPeriod getMeal() { return meal; }
    public String getRule() { return rule; }
    public double getObservedValue() { return observedValue; }
    public double getThreshold() { return threshold; }
    public String getAction() { return action; }
    public List<String> getCooldownLabels() { return cooldownLabels; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public OffsetDateTime getAcknowledgedAt() { return acknowledgedAt; }
}