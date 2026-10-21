package com.sustainabledining.workflow;

import com.sustainabledining.meal.MealType;
import com.sustainabledining.semester.Semester;
import jakarta.persistence.*;
import java.util.UUID;

/** A non-neutral policy that is blocked for the next N occurrences of one meal type. P0 is never cooled down. */
@Entity
@Table(name = "cooldown")
public class Cooldown {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(name = "policy_label", nullable = false, length = 2)
    private String policyLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 10)
    private MealType mealType;

    @Column(name = "remaining_occurrences", nullable = false)
    private int remainingOccurrences;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_event_id")
    private SafetyEvent sourceEvent;

    protected Cooldown() {}

    public Cooldown(Semester semester, String policyLabel, MealType mealType) {
        this.semester = semester;
        this.policyLabel = policyLabel;
        this.mealType = mealType;
    }

    public void decrement() {
        if (remainingOccurrences > 0) remainingOccurrences--;
    }

    public void reset(int occurrences, SafetyEvent source) {
        this.remainingOccurrences = occurrences;
        this.sourceEvent = source;
    }

    public UUID getId() { return id; }
    public String getPolicyLabel() { return policyLabel; }
    public MealType getMealType() { return mealType; }
    public int getRemainingOccurrences() { return remainingOccurrences; }
}