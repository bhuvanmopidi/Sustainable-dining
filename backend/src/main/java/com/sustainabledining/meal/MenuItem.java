package com.sustainabledining.meal;

import com.sustainabledining.catalog.Dish;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/** A dish on one meal's menu. Carbon factor and portion mass are COPIED from the dish when the menu
 *  is saved, so editing the dish later never changes past results. */
@Entity
@Table(name = "menu_item")
public class MenuItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meal_id", nullable = false)
    private MealPeriod meal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ItemRole role;

    @Column(name = "standard_slot", nullable = false)
    private int standardSlot;

    @Column(name = "carbon_factor_snapshot", nullable = false, precision = 8, scale = 4)
    private BigDecimal carbonFactorSnapshot;

    @Column(name = "portion_mass_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal portionMassSnapshot;

    @Column(name = "carbon_score", nullable = false, precision = 7, scale = 6)
    private BigDecimal carbonScore;

    protected MenuItem() {}

    public MenuItem(MealPeriod meal, Dish dish, ItemRole role, int standardSlot, BigDecimal carbonFactorSnapshot,
                    BigDecimal portionMassSnapshot, BigDecimal carbonScore) {
        this.meal = meal;
        this.dish = dish;
        this.role = role;
        this.standardSlot = standardSlot;
        this.carbonFactorSnapshot = carbonFactorSnapshot;
        this.portionMassSnapshot = portionMassSnapshot;
        this.carbonScore = carbonScore;
    }

    public UUID getId() { return id; }
    public MealPeriod getMeal() { return meal; }
    public Dish getDish() { return dish; }
    public ItemRole getRole() { return role; }
    public int getStandardSlot() { return standardSlot; }
    public BigDecimal getCarbonFactorSnapshot() { return carbonFactorSnapshot; }
    public BigDecimal getPortionMassSnapshot() { return portionMassSnapshot; }
    public BigDecimal getCarbonScore() { return carbonScore; }
}