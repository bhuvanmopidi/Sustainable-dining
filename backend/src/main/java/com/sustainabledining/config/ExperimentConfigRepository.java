package com.sustainabledining.config;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentConfigRepository extends JpaRepository<ExperimentConfig, UUID> {
    Optional<ExperimentConfig> findByVersion(String version);
}