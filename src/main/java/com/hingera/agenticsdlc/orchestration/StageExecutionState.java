package com.hingera.agenticsdlc.orchestration;

public record StageExecutionState(
        String stageId,
        String status,
        String message,
        boolean approvalRequired,
        boolean approved,
        boolean blocked
) {
    public StageExecutionState {
        status = status == null ? "PLANNED" : status;
        message = message == null ? "" : message;
    }
}
