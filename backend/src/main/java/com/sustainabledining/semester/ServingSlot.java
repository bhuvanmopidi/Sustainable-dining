package com.sustainabledining.semester;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/** One position on the serving line. Visibility 1.0 = most prominent. Fixed slots never move. */
@Entity
@Table(name = "serving_slot")
public class ServingSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal visibility;

    @Column(name = "eligible_slot", nullable = false)
    private boolean eligibleSlot;

    protected ServingSlot() {}

    public ServingSlot(Semester semester, int slotIndex, BigDecimal visibility, boolean eligibleSlot) {
        this.semester = semester;
        this.slotIndex = slotIndex;
        this.visibility = visibility;
        this.eligibleSlot = eligibleSlot;
    }

    public UUID getId() { return id; }
    public Semester getSemester() { return semester; }
    public int getSlotIndex() { return slotIndex; }
    public BigDecimal getVisibility() { return visibility; }
    public boolean isEligibleSlot() { return eligibleSlot; }
}