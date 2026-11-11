package com.hingera.agenticsdlc.orchestration;

import java.util.List;

public record StageDefinition(String id, String name, String description, List<String> dependencies,
                              boolean requiresHumanApproval, RiskLevel riskLevel) {
    public StageDefinition {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        riskLevel = riskLevel == null ? RiskLevel.MEDIUM : riskLevel;
    }
}
