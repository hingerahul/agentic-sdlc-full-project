package com.hingera.agenticsdlc.orchestration;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrchestrationStateService {
    private final Map<String, Map<String, StageExecutionRecord>> recordsByRun = new LinkedHashMap<>();

    public void initialize(String runId, List<StageDefinition> graph) {
        Map<String, StageExecutionRecord> records = new LinkedHashMap<>();
        for (StageDefinition stage : graph) {
            records.put(stage.id(), new StageExecutionRecord(
                    stage.id(), stage.name(), OrchestrationState.PLANNED, null, null, Instant.now(),
                    List.of(), 0, stage.requiresHumanApproval(), false, null));
        }
        recordsByRun.put(runId, records);
    }

    public Map<String, StageExecutionRecord> recordsFor(String runId) {
        return recordsByRun.getOrDefault(runId, Map.of());
    }

    public void transition(String runId, String stageId, OrchestrationState state, String failureReason, List<String> dependencyStates) {
        Map<String, StageExecutionRecord> records = recordsByRun.computeIfAbsent(runId, key -> new LinkedHashMap<>());
        StageExecutionRecord current = records.get(stageId);
        if (current == null) {
            records.put(stageId, new StageExecutionRecord(stageId, stageId, state, Instant.now(),
                    state == OrchestrationState.COMPLETED || state == OrchestrationState.BLOCKED ? Instant.now() : null,
                    Instant.now(), dependencyStates == null ? List.of() : dependencyStates, 0,
                    false, false, failureReason));
            return;
        }

        Instant started = current.startedAt();
        if (state == OrchestrationState.RUNNING && current.startedAt() == null) {
            started = Instant.now();
        }
        Instant completed = current.completedAt();
        if (state == OrchestrationState.COMPLETED || state == OrchestrationState.BLOCKED || state == OrchestrationState.SAFE_STOPPED) {
            completed = Instant.now();
        }

        records.put(stageId, new StageExecutionRecord(
                current.stageId(), current.stageName(), state, started, completed, Instant.now(),
                dependencyStates == null ? current.dependencyStates() : dependencyStates,
                current.retries(), current.humanApprovalRequired(), current.approved(), failureReason));
    }

    public void markApproval(String runId, String stageId, boolean approved) {
        Map<String, StageExecutionRecord> records = recordsByRun.computeIfAbsent(runId, key -> new LinkedHashMap<>());
        StageExecutionRecord current = records.get(stageId);
        if (current == null) return;
        records.put(stageId, new StageExecutionRecord(current.stageId(), current.stageName(), approved ? OrchestrationState.APPROVED : OrchestrationState.CHANGES_REQUESTED,
                current.startedAt(), Instant.now(), Instant.now(), current.dependencyStates(), current.retries(),
                current.humanApprovalRequired(), approved, current.failureReason()));
    }

    public void incrementRetry(String runId, String stageId) {
        Map<String, StageExecutionRecord> records = recordsByRun.computeIfAbsent(runId, key -> new LinkedHashMap<>());
        StageExecutionRecord current = records.get(stageId);
        if (current == null) return;
        records.put(stageId, new StageExecutionRecord(current.stageId(), current.stageName(), current.state(), current.startedAt(), current.completedAt(), Instant.now(),
                current.dependencyStates(), current.retries() + 1, current.humanApprovalRequired(), current.approved(), current.failureReason()));
    }
}
