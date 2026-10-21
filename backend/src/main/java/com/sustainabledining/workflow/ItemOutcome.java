package com.sustainabledining.workflow;

import com.sustainabledining.meal.MenuItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "item_outcome")
public class ItemOutcome {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_id", nullable = false)
    private Observation observation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(name = "portions_taken", nullable = false)
    private int portionsTaken;

    @Column(name = "waste_g", precision = 10, scale = 2)
    private BigDecimal wasteG;

    protected ItemOutcome() {}

    public ItemOutcome(Observation observation, MenuItem menuItem, int portionsTaken, BigDecimal wasteG) {
        this.observation = observation;
        this.menuItem = menuItem;
        this.portionsTaken = portionsTaken;
        this.wasteG = wasteG;
    }

    public UUID getId() { return id; }
    public MenuItem getMenuItem() { return menuItem; }
    public int getPortionsTaken() { return portionsTaken; }
    public BigDecimal getWasteG() { return wasteG; }
}