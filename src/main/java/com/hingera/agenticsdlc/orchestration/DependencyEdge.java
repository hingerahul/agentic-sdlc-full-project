package com.hingera.agenticsdlc.orchestration;

public record DependencyEdge(String fromStage, String toStage, String gateType) {
    public DependencyEdge {
        gateType = gateType == null ? ExecutionGate.EXIT : gateType;
    }
}
