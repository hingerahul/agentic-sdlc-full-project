package com.hingera.agenticsdlc.orchestration;

public record PolicyDecision(String ruleId, String outcome, String explanation) {
    public PolicyDecision {
        ruleId = ruleId == null ? "" : ruleId;
        outcome = outcome == null ? "" : outcome;
        explanation = explanation == null ? "" : explanation;
    }

    public boolean allowed() {
        return "ALLOW".equals(outcome);
    }
}
