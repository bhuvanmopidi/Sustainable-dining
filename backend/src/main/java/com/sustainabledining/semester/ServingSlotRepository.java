package com.sustainabledining.semester;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServingSlotRepository extends JpaRepository<ServingSlot, UUID> {
    List<ServingSlot> findBySemesterIdOrderBySlotIndexAsc(UUID semesterId);
}