package com.hingera.agenticsdlc.run;

import java.util.Map;
import java.util.Set;

public record WorkflowSnapshot(
        Map<String, String> stageStates,
        String pendingApproval,
        Set<String> approvedStages,
        Map<String, String> stageOutputs
) {
    public WorkflowSnapshot {
        stageStates = stageStates == null ? Map.of() : Map.copyOf(stageStates);
        approvedStages = approvedStages == null ? Set.of() : Set.copyOf(approvedStages);
        stageOutputs = stageOutputs == null ? Map.of() : Map.copyOf(stageOutputs);
    }
}
