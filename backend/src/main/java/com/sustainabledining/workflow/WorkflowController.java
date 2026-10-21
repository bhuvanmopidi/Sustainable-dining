package com.sustainabledining.workflow;

import com.sustainabledining.workflow.WorkflowDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkflowController {
    private final WorkflowService workflow;

    public WorkflowController(WorkflowService workflow) {
        this.workflow = workflow;
    }

    /** PLANNED -> RECOMMENDED: asks the ML service for the candidate layouts and its choice. */
    @PostMapping("/api/meals/{id}/recommend")
    public RecommendationView recommend(@PathVariable UUID id) {
        return workflow.recommend(id);
    }

    @GetMapping("/api/meals/{id}/recommendation")
    public RecommendationView recommendation(@PathVariable UUID id) {
        return workflow.getRecommendation(id);
    }

    /** RECOMMENDED -> DEPLOYED: accept (no candidateId) or override (candidateId + overrideReason). */
    @PostMapping("/api/meals/{id}/deploy")
    public DeploymentView deploy(@PathVariable UUID id, @Valid @RequestBody DeployRequest request) {
        return workflow.deploy(id, request);
    }

    /** DEPLOYED -> OBSERVED -> EVALUATED. If evaluation fails (ML service down), retry with /evaluate. */
    @PostMapping("/api/meals/{id}/observation")
    public EvaluationView observe(@PathVariable UUID id, @Valid @RequestBody ObservationRequest request) {
        workflow.observe(id, request, "MANUAL");
        return workflow.evaluate(id);
    }

    @PostMapping("/api/meals/{id}/evaluate")
    public EvaluationView evaluate(@PathVariable UUID id) {
        return workflow.evaluate(id);
    }

    @PostMapping("/api/semesters/{id}/reference/fit")
    public ReferenceView fitReference(@PathVariable UUID id) {
        return workflow.fitReference(id);
    }

    @GetMapping("/api/semesters/{id}/safety-events")
    public List<SafetyEventView> safetyEvents(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "false") boolean unacknowledged) {
        return workflow.safetyEvents(id, unacknowledged);
    }

    @PostMapping("/api/safety-events/{id}/acknowledge")
    public SafetyEventView acknowledge(@PathVariable UUID id, @Valid @RequestBody AcknowledgeRequest request) {
        return workflow.acknowledge(id, request.by());
    }
}