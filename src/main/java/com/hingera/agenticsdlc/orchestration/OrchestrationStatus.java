package com.hingera.agenticsdlc.orchestration;

public enum OrchestrationStatus {
    PLANNED,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    APPROVED,
    CHANGES_REQUESTED,
    SAFE_STOPPED,
    ROLLED_BACK
}
