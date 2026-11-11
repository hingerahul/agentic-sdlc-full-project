package com.hingera.agenticsdlc.orchestration;

public record RuntimeMetrics(int totalStages, int completedStages, int retries, int rollbacks,
                             int approvalCheckpoints, int criticalFailures, double successRate,
                             long endToEndLatencyMs, int fallbacks, long meanTimeToRecoveryMs) {
}
