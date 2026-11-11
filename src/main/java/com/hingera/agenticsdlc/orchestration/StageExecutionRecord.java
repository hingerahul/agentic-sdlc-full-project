package com.hingera.agenticsdlc.orchestration;

import java.time.Instant;
import java.util.List;

public record StageExecutionRecord(
        String stageId,
        String stageName,
        OrchestrationState state,
        Instant startedAt,
        Instant completedAt,
        Instant lastUpdatedAt,
        List<String> dependencyStates,
        int retries,
        boolean humanApprovalRequired,
        boolean approved,
        String failureReason
) {
    public StageExecutionRecord {
        dependencyStates = dependencyStates == null ? List.of() : List.copyOf(dependencyStates);
    }
}
