package com.sustainabledining.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ConfigLoaderTest {
    @Autowired ExperimentConfigRepository repository;
    @Autowired ExperimentConfigLoader loader;

    @Test
    void sharedConfigIsLoadedOnceAndMatchesTheFile() throws Exception {
        ExperimentConfig v1 = repository.findByVersion("v1").orElseThrow();
        assertThat(v1.getBody().get("feature_schema_version")).isEqualTo("v1");

        long before = repository.count();
        loader.run(null);                          // running again must not duplicate or fail
        assertThat(repository.count()).isEqualTo(before);
    }
}