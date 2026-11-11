package com.hingera.agenticsdlc.orchestration;

import java.util.List;

public record ExecutionGate(String stageId, String gateType, String description) {
    public static final String ENTRY = "ENTRY";
    public static final String EXIT = "EXIT";

    public ExecutionGate {
        gateType = gateType == null ? ENTRY : gateType;
        description = description == null ? "" : description;
    }
}

public record DependencyEdge(String fromStage, String toStage, String gateType) {
    public DependencyEdge {
        gateType = gateType == null ? ExecutionGate.ENTRY : gateType;
    }
}

public record StageDefinition(
        String id,
        String name,
        String description,
        List<String> dependencies,
        boolean requiresHumanApproval,
        RiskLevel riskLevel
) {
    public StageDefinition {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        riskLevel = riskLevel == null ? RiskLevel.MEDIUM : riskLevel;
    }
}

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

public record RuntimeMetrics(
        int totalStages,
        int completedStages,
        int retries,
        int rollbacks,
        int approvalCheckpoints,
        int criticalFailures,
        double successRate,
        long latencyMs
) {
}
