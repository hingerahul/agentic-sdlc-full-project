package com.hingera.agenticsdlc.orchestration;

import java.util.List;

public record ExecutionTrace(
        String stageId,
        String status,
        String message,
        List<String> dependencyChecks,
        boolean approved
) {
    public ExecutionTrace {
        dependencyChecks = dependencyChecks == null ? List.of() : List.copyOf(dependencyChecks);
    }
}
