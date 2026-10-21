package com.sustainabledining.meal;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.semester.Semester;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meal_period")
public class MealPeriod {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(name = "meal_index", nullable = false)
    private int mealIndex;

    @Column(name = "meal_date", nullable = false)
    private LocalDate mealDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 10)
    private MealType mealType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Phase phase;

    @Column(name = "expected_attendance", nullable = false)
    private int expectedAttendance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MealState state = MealState.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "eval_status", length = 30)
    private EvalStatus evalStatus;

    @Column(name = "force_p0", nullable = false)
    private boolean forceP0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "meal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("standardSlot ASC")
    private List<MenuItem> menuItems = new ArrayList<>();

    protected MealPeriod() {}

    public MealPeriod(Semester semester, int mealIndex, LocalDate mealDate, MealType mealType, Phase phase) {
        this.semester = semester;
        this.mealIndex = mealIndex;
        this.mealDate = mealDate;
        this.mealType = mealType;
        this.phase = phase;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    /** The only way to change state: one forward step at a time. */
    public void transitionTo(MealState next) {
        if (!state.canMoveTo(next)) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "meal " + mealIndex + " cannot move from " + state + " to " + next);
        }
        state = next;
    }

    public void setExpectedAttendance(int expectedAttendance) { this.expectedAttendance = expectedAttendance; }
    public void setEvalStatus(EvalStatus evalStatus) { this.evalStatus = evalStatus; }
    public void setForceP0(boolean forceP0) { this.forceP0 = forceP0; }

    public UUID getId() { return id; }
    public Semester getSemester() { return semester; }
    public int getMealIndex() { return mealIndex; }
    public LocalDate getMealDate() { return mealDate; }
    public MealType getMealType() { return mealType; }
    public Phase getPhase() { return phase; }
    public int getExpectedAttendance() { return expectedAttendance; }
    public MealState getState() { return state; }
    public EvalStatus getEvalStatus() { return evalStatus; }
    public boolean isForceP0() { return forceP0; }
    public List<MenuItem> getMenuItems() { return menuItems; }
}