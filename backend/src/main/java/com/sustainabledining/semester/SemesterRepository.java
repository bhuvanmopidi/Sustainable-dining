package com.sustainabledining.semester;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemesterRepository extends JpaRepository<Semester, UUID> {
    List<Semester> findAllByOrderByCreatedAtDesc();
}