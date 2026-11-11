package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.ExecutionTrace;
import com.hingera.agenticsdlc.orchestration.GovernancePolicyEngine;
import com.hingera.agenticsdlc.orchestration.OrchestrationStatus;
import com.hingera.agenticsdlc.orchestration.PolicyDecision;
import com.hingera.agenticsdlc.orchestration.RunOrchestrationService;
import com.hingera.agenticsdlc.orchestration.RuntimeMetrics;
import com.hingera.agenticsdlc.orchestration.StageDefinition;
import com.hingera.agenticsdlc.orchestration.StageWorker;
import com.hingera.agenticsdlc.security.AuthenticatedActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RunService {
    private static final int MAX_STAGE_ATTEMPTS = 2;
    private static final List<WorkItemDefinition> PLAN = List.of(
            new WorkItemDefinition(WorkItemKind.REQUIREMENT_ANALYSIS, "Understand requirement", "Capture intent, constraints, ambiguity, and acceptance evidence."),
            new WorkItemDefinition(WorkItemKind.DECOMPOSITION, "Decompose engineering work", "Create reviewable work items with sequencing and dependencies."),
            new WorkItemDefinition(WorkItemKind.PROTOTYPE_EXECUTION, "Prepare bounded engineering approach", "Produce a proposed approach only; this prototype does not modify code or call tools."),
            new WorkItemDefinition(WorkItemKind.VALIDATION, "Validate review package", "Check structural completeness; this is not functional or security validation."),
            new WorkItemDefinition(WorkItemKind.REVIEW_PACKAGE, "Prepare human review package", "Present plan, assumptions, risks, validation, and human decision gate.")
    );

    private final RunOrchestrationService orchestrationService = new RunOrchestrationService();
    private final RequirementUnderstandingService understandingService = new RequirementUnderstandingService();
    private final ConcurrentMap<UUID, EngineeringRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Map<String, String>> stageStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<RunAuditEvent>> auditEvents = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> pendingApprovals = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, java.util.Set<String>> approvedStages = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ConcurrentMap<String, String>> stageOutputs = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Object> runLocks = new ConcurrentHashMap<>();
    private final StageWorker stageWorker;
    private final GovernancePolicyEngine policyEngine;
    private final RunRepository runRepository;
    private final AuthenticatedActorProvider actorProvider;

    public RunService() {
        this(new com.hingera.agenticsdlc.orchestration.LocalPlanningStageWorker(),
                new GovernancePolicyEngine(), new InMemoryRunRepository(), new AuthenticatedActorProvider());
    }

    @PreAuthorize("hasRole('REVIEWER')")
    public EngineeringRun reviseStageOutput(UUID id, String stageId, StageOutputRevision revision) {
        findById(id);
        synchronized (runLocks.computeIfAbsent(id, ignored -> new Object())) {
            EngineeringRun current = findById(id);
            if (current.status() == RunStatus.APPROVED || current.status() == RunStatus.SAFE_STOPPED
                    || current.status() == RunStatus.CHANGES_REQUESTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Stage outputs can only be revised while a run is active or awaiting review");
            }
            if (revision == null || revision.output() == null || revision.output().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revised stage output cannot be blank");
            }
            StageDefinition stage = orchestrationService.defaultGraph().stream()
                    .filter(candidate -> candidate.id().equals(stageId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Unknown orchestration stage: " + stageId));
            if ("release-readiness".equals(stage.id())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Release readiness is derived from upstream evidence and cannot be revised directly");
            }
            Map<String, String> states = stageStates.get(id);
            Map<String, String> outputs = stageOutputs.get(id);
            if (!outputs.containsKey(stageId) || !isStagePassed(states.get(stageId))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a completed stage output can be revised");
            }

            List<String> invalidated = downstreamStages(stageId);
            invalidated.forEach(downstream -> {
                states.put(downstream, "PLANNED");
                outputs.remove(downstream);
                approvedStages.get(id).remove(downstream);
            });
            pendingApprovals.remove(id);
            approvedStages.get(id).remove(stageId);
            outputs.put(stageId, revision.output().trim());

            boolean approvalRequired = stage.requiresHumanApproval();
            if (approvalRequired) {
                states.put(stageId, "WAITING_APPROVAL");
                pendingApprovals.put(id, stageId);
            } else {
                states.put(stageId, "COMPLETED");
            }
            List<WorkItem> resetItems = current.workItems().stream().map(item -> new WorkItem(
                    item.order(), item.kind(), item.title(), item.description(), WorkItemStatus.PLANNED, "")).toList();
            EngineeringRun updated = new EngineeringRun(current.id(), current.requirement(),
                    approvalRequired ? RunStatus.REVIEW_REQUIRED : RunStatus.PLANNED,
                    current.createdAt(), Instant.now(), resetItems, "", null, null, "", current.assessment());
            saveRun(updated);
            String rationale = revision.rationale() == null || revision.rationale().isBlank()
                    ? "No rationale supplied." : revision.rationale().trim();
            record(id, "UPSTREAM_OUTPUT_REVISED", stageId, actorProvider.currentActor(),
                    "Reviewer revised output; invalidated downstream stages=" + invalidated
                            + "; rationale=" + rationale);
            if (approvalRequired) {
                record(id, "APPROVAL_REQUIRED", stageId, "runtime",
                        "Revised high-impact stage output requires renewed human approval before downstream work.");
                return updated;
            }
            return advance(updated);
        }
    }

    private List<String> downstreamStages(String stageId) {
        Map<String, StageDefinition> graph = orchestrationService.defaultGraph().stream()
                .collect(java.util.stream.Collectors.toMap(StageDefinition::id, stage -> stage));
        java.util.Set<String> downstream = new java.util.LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (StageDefinition candidate : graph.values()) {
                if (!candidate.id().equals(stageId) && !downstream.contains(candidate.id())
                        && candidate.dependencies().stream()
                        .anyMatch(dependency -> dependency.equals(stageId) || downstream.contains(dependency))) {
                    downstream.add(candidate.id());
                    changed = true;
                }
            }
        } while (changed);
        return List.copyOf(downstream);
    }

    @Autowired
    public RunService(StageWorker stageWorker, GovernancePolicyEngine policyEngine, RunRepository runRepository,
                      AuthenticatedActorProvider actorProvider) {
        this.stageWorker = stageWorker;
        this.policyEngine = policyEngine;
        this.runRepository = runRepository;
        this.actorProvider = actorProvider;
        restoreRuns();
    }

    public RunService(StageWorker stageWorker) {
        this(stageWorker, new GovernancePolicyEngine(), new InMemoryRunRepository(), new AuthenticatedActorProvider());
    }

    public RunService(StageWorker stageWorker, GovernancePolicyEngine policyEngine) {
        this(stageWorker, policyEngine, new InMemoryRunRepository(), new AuthenticatedActorProvider());
    }

    public RunService(StageWorker stageWorker, GovernancePolicyEngine policyEngine, RunRepository runRepository) {
        this(stageWorker, policyEngine, runRepository, new AuthenticatedActorProvider());
    }

    private void restoreRuns() {
        for (StoredRunState stored : runRepository.loadAll()) {
            UUID id = stored.run().id();
            WorkflowSnapshot workflow = stored.workflow() == null
                    ? new WorkflowSnapshot(null, null, null, null) : stored.workflow();
            runs.put(id, stored.run());
            stageStates.put(id, new ConcurrentHashMap<>(workflow.stageStates()));
            if (workflow.pendingApproval() != null) pendingApprovals.put(id, workflow.pendingApproval());
            approvedStages.put(id, ConcurrentHashMap.newKeySet());
            approvedStages.get(id).addAll(workflow.approvedStages());
            stageOutputs.put(id, new ConcurrentHashMap<>(workflow.stageOutputs()));
            auditEvents.put(id, new java.util.concurrent.CopyOnWriteArrayList<>(stored.events()));
        }
    }

    public EngineeringRun create(String rawRequirement) { return create(new CreateRunRequest(rawRequirement)); }

    public EngineeringRun create(CreateRunRequest request) {
        if (request == null || request.requirement() == null || request.requirement().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requirement cannot be blank");
        String requirement = request.requirement().trim();
        RequirementAssessment assessment = understandingService.assess(requirement, request.scenario(), request.codebaseContext(), request.acceptanceCriteria());
        Instant now = Instant.now();
        List<WorkItem> items = new ArrayList<>();
        for (int i = 0; i < PLAN.size(); i++) {
            WorkItemDefinition item = PLAN.get(i);
            items.add(new WorkItem(i + 1, item.kind(), item.title(), item.description(), WorkItemStatus.PLANNED, ""));
        }
        EngineeringRun run = new EngineeringRun(UUID.randomUUID(), requirement, RunStatus.PLANNED, now, now,
                items, "", null, null, "", assessment);
        Map<String, String> initialStates = new ConcurrentHashMap<>();
        orchestrationService.defaultGraph().forEach(stage -> initialStates.put(stage.id(), "PLANNED"));
        stageStates.put(run.id(), initialStates);
        auditEvents.put(run.id(), new java.util.concurrent.CopyOnWriteArrayList<>());
        approvedStages.put(run.id(), ConcurrentHashMap.newKeySet());
        stageOutputs.put(run.id(), new ConcurrentHashMap<>());
        saveRun(run);
        record(run.id(), "RUN_CREATED", null, actorProvider.currentActor(), "Run created; assessment=" + assessment.clarity());
        return run;
    }

    public List<EngineeringRun> findAll() { return runs.values().stream().sorted(Comparator.comparing(EngineeringRun::createdAt)).toList(); }

    public EngineeringRun findById(UUID id) {
        EngineeringRun run = runs.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + id);
        return run;
    }

    public List<RunAuditEvent> auditTrail(UUID id) {
        findById(id);
        return List.copyOf(auditEvents.getOrDefault(id, List.of()));
    }

    public OrchestrationSnapshot orchestrationSnapshot(UUID id) {
        EngineeringRun run = findById(id);
        List<StageDefinition> graph = orchestrationService.defaultGraph();
        Map<String, String> states = stageStates.getOrDefault(id, Map.of());
        List<ExecutionTrace> traces = graph.stream().map(stage -> {
            String state = states.getOrDefault(stage.id(), "PLANNED");
            List<String> checks = stage.dependencies().stream().map(dep -> dep + "=" + states.getOrDefault(dep, "PLANNED")).toList();
            boolean approved = approvedStages.getOrDefault(id, java.util.Set.of()).contains(stage.id());
            return new ExecutionTrace(stage.id(), state, stage.description(), checks, approved);
        }).toList();
        int completed = (int) states.values().stream().filter(state -> "COMPLETED".equals(state)
                || "COMPLETED_WITH_FALLBACK".equals(state) || "APPROVED".equals(state)).count();
        List<RunAuditEvent> events = auditEvents.getOrDefault(id, List.of());
        int retries = countEvents(events, "STAGE_RETRY");
        int fallbacks = countEvents(events, "STAGE_FALLBACK");
        int rollbacks = countEvents(events, "STAGE_ROLLBACK");
        int approvals = countEvents(events, "HUMAN_REVIEW");
        int failures = criticalFailureCount(events);
        Instant end = run.status() == RunStatus.APPROVED || run.status() == RunStatus.SAFE_STOPPED
                ? run.updatedAt() : Instant.now();
        long latency = Math.max(Duration.between(run.createdAt(), end).toMillis(), 0L);
        RuntimeMetrics metrics = new RuntimeMetrics(graph.size(), completed, retries, rollbacks,
                approvals, failures, graph.isEmpty() ? 0.0 : completed * 100.0 / graph.size(),
                latency, fallbacks, meanTimeToRecoveryMs(events));
        return new OrchestrationSnapshot(run.id(), run.requirement(), toOrchestrationStatus(run.status()),
                orchestrationService.buildPlanSummary(), traces, graph, orchestrationService.executionGates(),
                orchestrationService.executionWaves(), metrics);
    }

    public EngineeringRun execute(UUID id) {
        findById(id);
        synchronized (runLocks.computeIfAbsent(id, ignored -> new Object())) {
            return executeLocked(id);
        }
    }

    private EngineeringRun executeLocked(UUID id) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.PLANNED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a planned run can be executed; current status is " + current.status());
        List<String> graphIssues = orchestrationService.validationIssues();
        if (!graphIssues.isEmpty()) return safeStop(current, String.join("; ", graphIssues));

        return advance(current);
    }

    @PreAuthorize("hasRole('REVIEWER')")
    public EngineeringRun review(UUID id, ReviewRequest request) {
        findById(id);
        synchronized (runLocks.computeIfAbsent(id, ignored -> new Object())) {
            return reviewLocked(id, request);
        }
    }

    private EngineeringRun reviewLocked(UUID id, ReviewRequest request) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.REVIEW_REQUIRED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run must be awaiting review before a decision can be recorded");
        if (request == null || request.decision() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review decision is required");
        String stageId = pendingApprovals.get(id);
        if (stageId == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No approval checkpoint is pending");
        if ("release-readiness".equals(stageId) && (current.validation() == null || !current.validation().passed()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Release approval is blocked because validation did not pass");
        if (request.decision() == ReviewDecision.APPROVE && current.assessment().clarity() == RequirementClarity.NEEDS_CLARIFICATION)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolve requirement clarification questions before approval");
        Map<String, String> states = stageStates.get(id);
        boolean approved = request.decision() == ReviewDecision.APPROVE;
        states.put(stageId, approved ? "APPROVED" : "CHANGES_REQUESTED");
        pendingApprovals.remove(id);
        if (!approved) {
            EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), RunStatus.CHANGES_REQUESTED,
                    current.createdAt(), Instant.now(), current.workItems(), current.outcome(), current.validation(),
                    request.decision(), request.feedback() == null ? "" : request.feedback().trim(), current.assessment());
            saveRun(reviewed);
            record(id, "HUMAN_REVIEW", stageId, actorProvider.currentActor(),
                    request.decision() + "; feedback=" + (request.feedback() == null ? "" : request.feedback().trim()));
            return reviewed;
        }
        approvedStages.get(id).add(stageId);
        if ("release-readiness".equals(stageId)) {
            EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), RunStatus.APPROVED,
                    current.createdAt(), Instant.now(), current.workItems(), current.outcome(), current.validation(),
                    request.decision(), request.feedback() == null ? "" : request.feedback().trim(), current.assessment());
            saveRun(reviewed);
            record(id, "HUMAN_REVIEW", stageId, actorProvider.currentActor(),
                    request.decision() + "; feedback=" + (request.feedback() == null ? "" : request.feedback().trim()));
            return reviewed;
        }
        EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), RunStatus.PLANNED,
                current.createdAt(), Instant.now(), current.workItems(), current.outcome(), current.validation(),
                request.decision(), request.feedback() == null ? "" : request.feedback().trim(), current.assessment());
        saveRun(reviewed);
        record(id, "HUMAN_REVIEW", stageId, actorProvider.currentActor(),
                request.decision() + "; feedback=" + (request.feedback() == null ? "" : request.feedback().trim()));
        return advance(reviewed);
    }

    public EngineeringRun replan(UUID id, CreateRunRequest request) {
        findById(id);
        synchronized (runLocks.computeIfAbsent(id, ignored -> new Object())) {
            return replanLocked(id, request);
        }
    }

    private EngineeringRun replanLocked(UUID id, CreateRunRequest request) {
        EngineeringRun current = findById(id);
        if (current.status() == RunStatus.APPROVED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An approved run cannot be replanned");
        if (request == null || request.requirement() == null || request.requirement().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requirement cannot be blank");
        String requirement = request.requirement().trim();
        RequirementAssessment assessment = understandingService.assess(requirement, request.scenario(),
                request.codebaseContext(), request.acceptanceCriteria());
        List<WorkItem> planned = new ArrayList<>();
        for (int i = 0; i < PLAN.size(); i++) {
            WorkItemDefinition item = PLAN.get(i);
            planned.add(new WorkItem(i + 1, item.kind(), item.title(), item.description(), WorkItemStatus.PLANNED, ""));
        }
        EngineeringRun replanned = new EngineeringRun(id, requirement, RunStatus.PLANNED, current.createdAt(),
                Instant.now(), planned, "", null, null, "", assessment);
        orchestrationService.defaultGraph().forEach(stage -> stageStates.get(id).put(stage.id(), "PLANNED"));
        pendingApprovals.remove(id);
        approvedStages.get(id).clear();
        stageOutputs.get(id).clear();
        saveRun(replanned);
        record(id, "RUN_REPLANNED", null, actorProvider.currentActor(),
                "Prior stage outputs and approvals invalidated; new assessment=" + assessment.clarity());
        return replanned;
    }

    private EngineeringRun advance(EngineeringRun current) {
        UUID id = current.id();
        Map<String, String> states = stageStates.get(id);
        Map<String, StageDefinition> graph = orchestrationService.defaultGraph().stream()
                .collect(java.util.stream.Collectors.toMap(StageDefinition::id, stage -> stage));
        for (List<String> wave : orchestrationService.executionWaves()) {
            List<StageDefinition> ready = wave.stream().map(graph::get)
                    .filter(stage -> !isStageExecuted(id, stage.id(), states.get(stage.id()))).toList();
            if (ready.isEmpty()) continue;
            for (StageDefinition stage : ready) {
                boolean dependenciesComplete = stage.dependencies().stream()
                        .allMatch(dependency -> isStagePassed(states.get(dependency)));
                if (!dependenciesComplete) return safeStop(current, "Entry gate failed for stage " + stage.id());
                PolicyDecision decision = policyEngine.evaluateStage(stage, approvedStages.get(id).contains(stage.id()));
                if (!"release-readiness".equals(stage.id()) && "DENY".equals(decision.outcome())) {
                    record(id, "POLICY_DENIED", stage.id(), "policy-engine",
                            decision.ruleId() + ": " + decision.explanation());
                    return safeStop(current, "Policy denied stage " + stage.id() + ": " + decision.explanation());
                }
                if (!"release-readiness".equals(stage.id()) && "HOLD".equals(decision.outcome())) {
                    record(id, "POLICY_CHECK", stage.id(), "policy-engine",
                            decision.outcome() + " " + decision.ruleId() + ": " + decision.explanation());
                    states.put(stage.id(), "WAITING_APPROVAL");
                    pendingApprovals.put(id, stage.id());
                    EngineeringRun waiting = awaitingApproval(current);
                    record(id, "APPROVAL_REQUIRED", stage.id(), "runtime",
                            "Entry gate passed; approval is required before this high-impact stage. Dependencies=" + stage.dependencies());
                    return waiting;
                }
            }

            List<String> failedStages = executeWave(id, ready, current);
            if (!failedStages.isEmpty()) {
                failedStages.forEach(stage -> states.put(stage, "BLOCKED"));
                return safeStop(current, "Stage execution failed after bounded retry/fallback policy: " + failedStages);
            }

            StageDefinition release = ready.stream().filter(stage -> stage.id().equals("release-readiness"))
                    .findFirst().orElse(null);
            if (release != null) {
                List<WorkItem> completed = current.workItems().stream().map(item -> new WorkItem(item.order(),
                        item.kind(), item.title(), item.description(), WorkItemStatus.COMPLETED, outputFor(item.kind(), current))).toList();
                ValidationResult validation = validate(id, current.requirement(), completed, current.assessment());
                PolicyDecision releaseDecision = policyEngine.evaluateRelease(validation);
                if (!"HOLD".equals(releaseDecision.outcome())) {
                    record(id, "POLICY_DENIED", release.id(), "policy-engine",
                            releaseDecision.ruleId() + ": " + releaseDecision.explanation());
                    return safeStop(current, "Release entry policy denied: " + validation.issues());
                }
                EngineeringRun packaged = new EngineeringRun(current.id(), current.requirement(), RunStatus.REVIEW_REQUIRED,
                        current.createdAt(), Instant.now(), completed, buildOutcome(current, completed, validation),
                        validation, null, "", current.assessment());
                states.put(release.id(), "WAITING_APPROVAL");
                pendingApprovals.put(id, release.id());
                saveRun(packaged);
                record(id, "POLICY_CHECK", release.id(), "policy-engine",
                        releaseDecision.outcome() + " " + releaseDecision.ruleId() + ": " + releaseDecision.explanation());
                record(id, "APPROVAL_REQUIRED", release.id(), "runtime", "Release evidence is ready; final human approval is required.");
                return packaged;
            }
        }
        return safeStop(current, "Workflow graph ended without release-readiness evidence.");
    }

    private List<String> executeWave(UUID runId, List<StageDefinition> wave, EngineeringRun run) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Boolean>> executions = wave.stream()
                    .map(stage -> CompletableFuture.supplyAsync(() -> executeStage(runId, stage, run), executor))
                    .toList();
            return java.util.stream.IntStream.range(0, executions.size())
                    .filter(index -> !executions.get(index).join())
                    .mapToObj(index -> wave.get(index).id()).toList();
        }
    }

    private boolean executeStage(UUID runId, StageDefinition stage, EngineeringRun run) {
        Map<String, String> states = stageStates.get(runId);
        PolicyDecision capability = policyEngine.evaluateCapability("LOCAL_PLANNING");
        if (!capability.allowed()) {
            record(runId, "POLICY_DENIED", stage.id(), "policy-engine",
                    capability.ruleId() + ": " + capability.explanation());
            return false;
        }
        record(runId, "POLICY_CHECK", stage.id(), "policy-engine",
                capability.outcome() + " " + capability.ruleId() + ": " + capability.explanation());
        states.put(stage.id(), "RUNNING");
        record(runId, "STAGE_STARTED", stage.id(), "runtime",
                "Entry gate passed; dependencies=" + stage.dependencies() + "; prior stage outputs="
                        + stageOutputs.get(runId).keySet());
        Instant firstFailure = null;
        for (int attempt = 1; attempt <= MAX_STAGE_ATTEMPTS; attempt++) {
            try {
                String output = stageWorker.execute(stage, run, Map.copyOf(stageOutputs.get(runId)));
                if (output == null || output.isBlank())
                    throw new IllegalStateException("Stage worker returned empty output");
                stageOutputs.get(runId).put(stage.id(), output);
                states.put(stage.id(), "COMPLETED");
                record(runId, "STAGE_COMPLETED", stage.id(), "runtime",
                        "Exit gate passed on attempt " + attempt + "; output recorded.");
                if (firstFailure != null) {
                    record(runId, "STAGE_RECOVERED", stage.id(), "runtime",
                            "Stage recovered after " + Duration.between(firstFailure, Instant.now()).toMillis() + " ms.");
                }
                return true;
            } catch (Exception failure) {
                if (firstFailure == null) firstFailure = Instant.now();
                if (attempt < MAX_STAGE_ATTEMPTS) {
                    record(runId, "STAGE_RETRY", stage.id(), "runtime",
                            "Attempt " + attempt + " failed (" + failure.getClass().getSimpleName() + "); retry budget remains.");
                    continue;
                }
                record(runId, "STAGE_FAILED", stage.id(), "runtime",
                        "Attempt " + attempt + " failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                if (stage.riskLevel() != com.hingera.agenticsdlc.orchestration.RiskLevel.HIGH) {
                    stageOutputs.get(runId).put(stage.id(), "Fallback plan: " + stage.description()
                            + " Original execution failed; manual verification is required.");
                    states.put(stage.id(), "COMPLETED_WITH_FALLBACK");
                    record(runId, "STAGE_FALLBACK", stage.id(), "runtime",
                            "Used non-mutating fallback after bounded retries; release validation remains mandatory.");
                    record(runId, "STAGE_RECOVERED", stage.id(), "runtime",
                            "Fallback completed after " + Duration.between(firstFailure, Instant.now()).toMillis() + " ms.");
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean isStagePassed(String state) {
        return "COMPLETED".equals(state) || "COMPLETED_WITH_FALLBACK".equals(state) || "APPROVED".equals(state);
    }

    private boolean isStageExecuted(UUID runId, String stageId, String state) {
        return "COMPLETED".equals(state) || "COMPLETED_WITH_FALLBACK".equals(state)
                || ("APPROVED".equals(state) && stageOutputs.get(runId).containsKey(stageId));
    }

    private int countEvents(List<RunAuditEvent> events, String type) {
        return (int) events.stream().filter(event -> type.equals(event.eventType())).count();
    }

    private int criticalFailureCount(List<RunAuditEvent> events) {
        return countEvents(events, "SAFE_STOP");
    }

    private long meanTimeToRecoveryMs(List<RunAuditEvent> events) {
        Map<String, Instant> failures = new ConcurrentHashMap<>();
        List<Long> recoveries = new ArrayList<>();
        for (RunAuditEvent event : events) {
            if ("STAGE_RETRY".equals(event.eventType())) {
                failures.putIfAbsent(event.stageId(), event.occurredAt());
            } else if ("STAGE_RECOVERED".equals(event.eventType()) && event.stageId() != null) {
                Instant failure = failures.remove(event.stageId());
                if (failure != null) recoveries.add(Math.max(Duration.between(failure, event.occurredAt()).toMillis(), 0L));
            }
        }
        return recoveries.isEmpty() ? 0L : (long) recoveries.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private EngineeringRun awaitingApproval(EngineeringRun current) {
        EngineeringRun waiting = new EngineeringRun(current.id(), current.requirement(), RunStatus.REVIEW_REQUIRED,
                current.createdAt(), Instant.now(), current.workItems(), current.outcome(), current.validation(),
                current.reviewDecision(), current.reviewFeedback(), current.assessment());
        saveRun(waiting);
        return waiting;
    }

    private EngineeringRun safeStop(EngineeringRun current, String reason) {
        Map<String, String> states = stageStates.get(current.id());
        List<String> rolledBack = states.entrySet().stream()
                .filter(entry -> isStageExecuted(current.id(), entry.getKey(), entry.getValue()))
                .map(Map.Entry::getKey).toList();
        states.replaceAll((stage, state) -> {
            if (rolledBack.contains(stage)) return "ROLLED_BACK";
            return "PLANNED".equals(state) || "RUNNING".equals(state) ? "BLOCKED" : state;
        });
        rolledBack.forEach(stage -> stageOutputs.get(current.id()).remove(stage));
        EngineeringRun stopped = new EngineeringRun(current.id(), current.requirement(), RunStatus.SAFE_STOPPED,
                current.createdAt(), Instant.now(), current.workItems(), "Execution stopped safely: " + reason,
                current.validation(), null, "", current.assessment());
        saveRun(stopped);
        rolledBack.forEach(stage -> record(current.id(), "STAGE_ROLLBACK", stage, "runtime",
                "Invalidated completed local planning output; no external changes existed to reverse."));
        record(current.id(), "SAFE_STOP", null, "runtime", reason);
        return stopped;
    }

    private void record(UUID runId, String type, String stage, String actor, String detail) {
        RunAuditEvent event = new RunAuditEvent(UUID.randomUUID(), runId, Instant.now(), type, stage, actor, detail);
        auditEvents.computeIfAbsent(runId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(event);
        runRepository.appendEvent(event, workflowSnapshot(runId));
    }

    private void saveRun(EngineeringRun run) {
        runRepository.saveRun(run);
        runs.put(run.id(), run);
    }

    private WorkflowSnapshot workflowSnapshot(UUID runId) {
        return new WorkflowSnapshot(stageStates.get(runId), pendingApprovals.get(runId),
                approvedStages.get(runId), stageOutputs.get(runId));
    }

    private String outputFor(WorkItemKind kind, EngineeringRun run) {
        Map<String, String> outputs = stageOutputs.get(run.id());
        return switch (kind) {
            case REQUIREMENT_ANALYSIS -> outputs.getOrDefault("requirement-analysis", "Requirement analysis output is unavailable.");
            case DECOMPOSITION -> combineOutputs(outputs, "codebase-analysis", "architecture-design",
                    "implementation");
            case PROTOTYPE_EXECUTION -> "Implementation planning only; no source code, repository, or external system was changed.\n\n"
                    + outputs.getOrDefault("implementation", "Implementation plan is unavailable.");
            case VALIDATION -> "Structural/reviewability validation only; target acceptance correctness, security, and compliance were not executed.\n\n"
                    + combineOutputs(outputs, "testing", "release-readiness");
            case REVIEW_PACKAGE -> "Human-owned review required; no automatic approval is performed.\n\n"
                    + outputs.getOrDefault("documentation", "Documentation plan is unavailable.");
        };
    }

    private String combineOutputs(Map<String, String> outputs, String... stageIds) {
        return java.util.Arrays.stream(stageIds)
                .map(stage -> "### " + stage + "\n" + outputs.getOrDefault(stage, "No output available."))
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private ValidationResult validate(UUID runId, String requirement, List<WorkItem> items, RequirementAssessment assessment) {
        List<String> checks = new ArrayList<>(), issues = new ArrayList<>();
        check(!requirement.isBlank(), "Requirement is present", checks, issues);
        check(items.size() == PLAN.size(), "Expected workflow items are present", checks, issues);
        check(items.stream().allMatch(item -> item.status() == WorkItemStatus.COMPLETED), "All workflow items completed", checks, issues);
        check(items.stream().allMatch(item -> item.output() != null && !item.output().isBlank()), "Every item has an execution note", checks, issues);
        check(stageOutputs.get(runId).containsKey("testing"), "Testing stage produced an output", checks, issues);
        check(stageOutputs.get(runId).containsKey("documentation"), "Documentation stage produced an output", checks, issues);
        check(!"COMPLETED_WITH_FALLBACK".equals(stageStates.get(runId).get("testing")),
                "Testing stage completed without fallback", checks, issues);
        check(assessment.clarity() == RequirementClarity.CLEAR, "Requirement is sufficiently clear for approval", checks, issues);
        if (assessment.clarity() != RequirementClarity.CLEAR) issues.addAll(assessment.ambiguityReasons());
        return new ValidationResult(issues.isEmpty(), checks, issues);
    }

    private void check(boolean passed, String label, List<String> checks, List<String> issues) {
        checks.add((passed ? "PASS: " : "FAIL: ") + label);
        if (!passed) issues.add(label);
    }

    private String buildOutcome(EngineeringRun run, List<WorkItem> items, ValidationResult validation) {
        StringBuilder doc = new StringBuilder("# Engineering outcome\n\n## Submitted requirement\n\n")
                .append(run.requirement()).append("\n\n## Assessment\n\nScenario: ").append(run.assessment().scenario())
                .append("\nClarity: ").append(run.assessment().clarity()).append("\n\n## Clarification questions\n\n");
        appendList(doc, run.assessment().clarificationQuestions());
        doc.append("\n## Assumptions\n\n"); appendList(doc, run.assessment().assumptions());
        doc.append("\n## Acceptance criteria\n\n"); appendList(doc, run.assessment().acceptanceCriteria());
        doc.append("\n## Supplied codebase context\n\n")
                .append(run.assessment().suppliedCodebaseContext().isBlank()
                        ? "No codebase context was supplied.\n" : run.assessment().suppliedCodebaseContext() + "\n");
        doc.append("\n## Plan and outputs\n\n");
        for (WorkItem item : items) doc.append(item.order()).append(". **").append(item.title()).append("** — ").append(item.output()).append("\n\n");
        doc.append("## Validation\n\n"); appendList(doc, validation.checks());
        doc.append("\n## Stage decisions\n\n");
        stageOutputs.getOrDefault(run.id(), new ConcurrentHashMap<>()).forEach((stage, output) ->
                doc.append("- **").append(stage).append("** — ").append(output).append('\n'));
        doc.append("\n## Risks and limitations\n\n- Independent local planning stages can execute concurrently; this runtime does not dispatch external agents or jobs.\n")
                .append("- Run and workflow state persist in local H2; ordered audit events are HMAC-chained and verified at load. HTTP Basic uses configured local requester/reviewer accounts; data is not encrypted at rest and the audit store is not immutable.\n")
                .append("- Retry and rollback operate on local planning outputs only; no code generation, repository inspection/mutation, CI, or security/compliance scan is performed.\n")
                .append("- Structural validation is not functional correctness; human review owns final quality.\n");
        return doc.toString();
    }

    private void appendList(StringBuilder doc, List<String> values) {
        if (values.isEmpty()) doc.append("- None recorded.\n"); else values.forEach(value -> doc.append("- ").append(value).append('\n'));
    }

    private OrchestrationStatus toOrchestrationStatus(RunStatus status) {
        return switch (status) {
            case PLANNED -> OrchestrationStatus.PLANNED;
            case REVIEW_REQUIRED -> OrchestrationStatus.WAITING_APPROVAL;
            case APPROVED -> OrchestrationStatus.APPROVED;
            case CHANGES_REQUESTED -> OrchestrationStatus.CHANGES_REQUESTED;
            case SAFE_STOPPED -> OrchestrationStatus.SAFE_STOPPED;
        };
    }

    private record WorkItemDefinition(WorkItemKind kind, String title, String description) { }
}
