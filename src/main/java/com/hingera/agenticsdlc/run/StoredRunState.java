package com.hingera.agenticsdlc.run;

import java.util.List;

public record StoredRunState(EngineeringRun run, WorkflowSnapshot workflow, List<RunAuditEvent> events) {
    public StoredRunState {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
