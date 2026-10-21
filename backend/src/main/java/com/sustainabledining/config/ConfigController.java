package com.sustainabledining.config;

import com.sustainabledining.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final ExperimentConfigRepository repository;

    public ConfigController(ExperimentConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<String> versions() {
        return repository.findAll().stream().map(ExperimentConfig::getVersion).sorted().toList();
    }

    @GetMapping("/{version}")
    public Map<String, Object> get(@PathVariable String version) {
        return repository.findByVersion(version)
                .orElseThrow(() -> ApiException.notFound("config", version))
                .getBody();
    }
}