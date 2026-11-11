package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.ExecutionTrace;
import com.hingera.agenticsdlc.orchestration.OrchestrationStatus;
import com.hingera.agenticsdlc.orchestration.RuntimeMetrics;
import com.hingera.agenticsdlc.orchestration.StageDefinition;

import java.util.List;
import java.util.UUID;

public record OrchestrationSnapshot(
        UUID id,
        String requirement,
        OrchestrationStatus status,
        String summary,
        List<ExecutionTrace> traces,
        List<StageDefinition> graph,
        RuntimeMetrics metrics
) {
    public OrchestrationSnapshot {
        traces = traces == null ? List.of() : List.copyOf(traces);
        graph = graph == null ? List.of() : List.copyOf(graph);
    }
}
