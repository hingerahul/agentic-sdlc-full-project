package com.hingera.agenticsdlc.orchestration;

public record ExecutionGate(String stageId, String gateType, String description) {
    public static final String ENTRY = "ENTRY";
    public static final String EXIT = "EXIT";

    public ExecutionGate {
        gateType = gateType == null ? ENTRY : gateType;
        description = description == null ? "" : description;
    }
}
