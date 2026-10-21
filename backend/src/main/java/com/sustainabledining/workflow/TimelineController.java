package com.sustainabledining.workflow;

import com.sustainabledining.workflow.TimelineService.TimelineRow;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class TimelineController {
    private final TimelineService timeline;

    public TimelineController(TimelineService timeline) {
        this.timeline = timeline;
    }

    /** Every meal of the semester in order, with its deployed layout, metrics and safety events. */
    @GetMapping("/api/semesters/{id}/timeline")
    public List<TimelineRow> timeline(@PathVariable UUID id) {
        return timeline.timeline(id);
    }
}