package com.hingera.agenticsdlc.run;

import java.util.List;
import java.util.UUID;

public interface RunRepository {
    List<StoredRunState> loadAll();

    void saveRun(EngineeringRun run);

    void appendEvent(RunAuditEvent event, WorkflowSnapshot workflow);
}
