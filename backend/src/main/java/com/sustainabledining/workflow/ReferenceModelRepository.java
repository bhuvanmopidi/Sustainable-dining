package com.sustainabledining.workflow;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceModelRepository extends JpaRepository<ReferenceModelEntity, UUID> {
    Optional<ReferenceModelEntity> findBySemesterId(UUID semesterId);
}