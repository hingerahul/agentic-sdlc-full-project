package com.hingera.agenticsdlc.orchestration;

public enum OrchestrationState {
    PLANNED,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    BLOCKED,
    SAFE_STOPPED,
    ROLLED_BACK,
    CHANGES_REQUESTED,
    APPROVED
}
