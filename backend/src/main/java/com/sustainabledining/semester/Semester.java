package com.sustainabledining.semester;

import com.sustainabledining.config.ExperimentConfig;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "semester")
public class Semester {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SemesterMode mode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "enrolled_students", nullable = false)
    private int enrolledStudents;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_id", nullable = false)
    private ExperimentConfig config;

    @Column(name = "sim_seed")
    private Integer simSeed;

    @Column(name = "sim_scenario", length = 40)
    private String simScenario;

    @Column(name = "reference_model_id")
    private UUID referenceModelId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Semester() {}

    public Semester(String name, SemesterMode mode, LocalDate startDate, int enrolledStudents,
                    ExperimentConfig config, Integer simSeed, String simScenario) {
        this.name = name;
        this.mode = mode;
        this.startDate = startDate;
        this.enrolledStudents = enrolledStudents;
        this.config = config;
        this.simSeed = simSeed;
        this.simScenario = simScenario;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public void setReferenceModelId(UUID referenceModelId) { this.referenceModelId = referenceModelId; }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public SemesterMode getMode() { return mode; }
    public LocalDate getStartDate() { return startDate; }
    public int getEnrolledStudents() { return enrolledStudents; }
    public ExperimentConfig getConfig() { return config; }
    public Integer getSimSeed() { return simSeed; }
    public String getSimScenario() { return simScenario; }
    public UUID getReferenceModelId() { return referenceModelId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}