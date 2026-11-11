package com.hingera.agenticsdlc.run;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryRunRepository implements RunRepository {
    private final ConcurrentMap<UUID, EngineeringRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, WorkflowSnapshot> workflows = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<RunAuditEvent>> events = new ConcurrentHashMap<>();

    @Override
    public List<StoredRunState> loadAll() {
        List<StoredRunState> storedRuns = new ArrayList<>();
        runs.forEach((id, run) -> storedRuns.add(new StoredRunState(run,
                workflows.getOrDefault(id, new WorkflowSnapshot(null, null, null, null)),
                events.getOrDefault(id, List.of()))));
        return List.copyOf(storedRuns);
    }

    @Override
    public void saveRun(EngineeringRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public void appendEvent(RunAuditEvent event, WorkflowSnapshot workflow) {
        events.computeIfAbsent(event.runId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
        workflows.put(event.runId(), workflow);
    }
}
