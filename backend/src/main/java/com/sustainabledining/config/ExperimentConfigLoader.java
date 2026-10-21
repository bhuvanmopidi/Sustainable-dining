package com.sustainabledining.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads config/experiment_config.*.json (the same file Python uses) into the database at startup.
 * A version is immutable: if the file changed but kept its version, startup fails rather than
 * silently changing an experiment that is already running.
 */
@Component
public class ExperimentConfigLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ExperimentConfigLoader.class);

    private final ExperimentConfigRepository repository;
    private final ObjectMapper mapper;
    private final Path configPath;

    public ExperimentConfigLoader(ExperimentConfigRepository repository, ObjectMapper mapper,
                                  @Value("${dining.config-path}") String configPath) {
        this.repository = repository;
        this.mapper = mapper;
        this.configPath = Path.of(configPath);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Map<String, Object> body = mapper.readValue(Files.readString(configPath), new TypeReference<>() {});
        String version = (String) body.get("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("config file " + configPath + " has no version");
        }
        repository.findByVersion(version).ifPresentOrElse(existing -> {
            if (!mapper.valueToTree(existing.getBody()).equals(mapper.valueToTree(body))) {
                throw new IllegalStateException("config " + version + " changed on disk but versions are immutable; "
                        + "save the change as a new version instead");
            }
        }, () -> {
            repository.save(new ExperimentConfig(version, body));
            log.info("Loaded experiment config {} from {}", version, configPath.toAbsolutePath());
        });
    }
}