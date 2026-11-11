package com.hingera.agenticsdlc.orchestration;

import com.hingera.agenticsdlc.run.ValidationResult;
import org.springframework.stereotype.Service;

@Service
public class GovernancePolicyEngine {
    public PolicyDecision evaluateStage(StageDefinition stage, boolean humanApproved) {
        if (stage.riskLevel() == RiskLevel.HIGH && !stage.requiresHumanApproval()) {
            return new PolicyDecision("HIGH_RISK_APPROVAL", "DENY",
                    "High-risk work must declare a human approval checkpoint.");
        }
        if (stage.requiresHumanApproval() && !humanApproved) {
            return new PolicyDecision("HUMAN_APPROVAL_REQUIRED", "HOLD",
                    "Execution cannot proceed until a human approval is recorded.");
        }
        return new PolicyDecision("LOCAL_PLANNING_ONLY", "ALLOW",
                "Only local planning work is enabled; source writes and external actions are disabled.");
    }

    public PolicyDecision evaluateCapability(String capability) {
        if ("LOCAL_PLANNING".equals(capability)) {
            return new PolicyDecision("LOCAL_PLANNING_ONLY", "ALLOW", "Local planning is permitted.");
        }
        return new PolicyDecision("EXTERNAL_ACTIONS_DISABLED", "DENY",
                "Repository mutation, code execution, network access, and deployment are disabled.");
    }

    public PolicyDecision evaluateRelease(ValidationResult validation) {
        if (validation == null || !validation.passed()) {
            return new PolicyDecision("RELEASE_VALIDATION_REQUIRED", "DENY",
                    "Release approval requires passing structural validation.");
        }
        return new PolicyDecision("RELEASE_HUMAN_APPROVAL", "HOLD",
                "Validation passed; final release approval remains a human decision.");
    }
}
