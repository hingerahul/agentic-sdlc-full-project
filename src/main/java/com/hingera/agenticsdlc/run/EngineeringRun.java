package com.hingera.agenticsdlc.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EngineeringRun(
        UUID id,
        String requirement,
        RunStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<WorkItem> workItems,
        String outcome,
        ValidationResult validation,
        ReviewDecision reviewDecision,
        String reviewFeedback
) {
    public EngineeringRun {
        workItems = List.copyOf(workItems);
    }
}
