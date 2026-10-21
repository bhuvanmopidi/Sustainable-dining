package com.sustainabledining.workflow;

import com.sustainabledining.semester.Semester;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The frozen neutral reference, fitted once at the end of the baseline. Body = reference_model contract. */
@Entity
@Table(name = "reference_model")
public class ReferenceModelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false, unique = true)
    private Semester semester;

    @Column(nullable = false, length = 40)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ReferenceModelEntity() {}

    public ReferenceModelEntity(Semester semester, String version, Map<String, Object> body) {
        this.semester = semester;
        this.version = version;
        this.body = body;
    }

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public Semester getSemester() { return semester; }
    public String getVersion() { return version; }
    public Map<String, Object> getBody() { return body; }
}