package com.sustainabledining.demo;

import com.sustainabledining.workflow.WorkflowDtos.EvaluationView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class DemoController {
    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    /** Runs the next `meals` meals of a SIMULATED_DEMO semester end to end (default 1). */
    @PostMapping("/api/semesters/{id}/demo/run")
    public List<EvaluationView> run(@PathVariable UUID id, @RequestParam(defaultValue = "1") int meals) {
        return demo.run(id, Math.max(1, Math.min(meals, 336)));
    }
}