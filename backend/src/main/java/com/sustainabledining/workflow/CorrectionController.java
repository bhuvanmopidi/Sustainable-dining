package com.sustainabledining.workflow;

import com.sustainabledining.workflow.WorkflowDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class CorrectionController {
    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    /** Replaces the observation of an EVALUATED meal (new revision) and re-evaluates it and all later meals. */
    @PostMapping("/api/meals/{id}/observation/correction")
    public CorrectionView correct(@PathVariable UUID id, @Valid @RequestBody CorrectionRequest request) {
        return corrections.correct(id, request);
    }

    /** Every revision of the meal's observation, oldest first; the last one is the one in use. */
    @GetMapping("/api/meals/{id}/observations")
    public List<ObservationView> observations(@PathVariable UUID id) {
        return corrections.history(id);
    }
}