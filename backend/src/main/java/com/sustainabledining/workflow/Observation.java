package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealPeriod;
import com.sustainabledining.meal.MenuItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What a manager can observe after a meal. Per-dish waste stays null unless genuinely measured. */
@Entity
@Table(name = "observation")
public class Observation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @Column(nullable = false)
    private int revision;

    @Column(name = "actual_attendance", nullable = false)
    private int actualAttendance;

    @Column(name = "aggregate_waste_g", nullable = false, precision = 12, scale = 2)
    private BigDecimal aggregateWasteG;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "entered_by", nullable = false, length = 100)
    private String enteredBy;

    /** Why this revision replaced the previous one (null for the first revision). */
    @Column(name = "correction_reason")
    private String correctionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "observation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemOutcome> items = new ArrayList<>();

    protected Observation() {}

    public Observation(MealPeriod meal, int revision, int actualAttendance, BigDecimal aggregateWasteG, String source,
                       String enteredBy) {
        this.meal = meal;
        this.revision = revision;
        this.actualAttendance = actualAttendance;
        this.aggregateWasteG = aggregateWasteG;
        this.source = source;
        this.enteredBy = enteredBy;
    }

    public Observation(MealPeriod meal, int revision, int actualAttendance, BigDecimal aggregateWasteG, String source,
                       String enteredBy, String correctionReason) {
        this(meal, revision, actualAttendance, aggregateWasteG, source, enteredBy);
        this.correctionReason = correctionReason;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public void addItem(MenuItem menuItem, int portionsTaken, BigDecimal wasteG) {
        items.add(new ItemOutcome(this, menuItem, portionsTaken, wasteG));
    }

    public UUID getId() { return id; }
    public MealPeriod getMeal() { return meal; }
    public int getRevision() { return revision; }
    public int getActualAttendance() { return actualAttendance; }
    public BigDecimal getAggregateWasteG() { return aggregateWasteG; }
    public String getSource() { return source; }
    public String getEnteredBy() { return enteredBy; }
    public String getCorrectionReason() { return correctionReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<ItemOutcome> getItems() { return items; }
}