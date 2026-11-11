package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.ExecutionTrace;
import com.hingera.agenticsdlc.orchestration.OrchestrationStatus;
import com.hingera.agenticsdlc.orchestration.RunOrchestrationService;
import com.hingera.agenticsdlc.orchestration.RuntimeMetrics;
import com.hingera.agenticsdlc.orchestration.StageDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RunService {
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
    private final ConcurrentMap<UUID, Instant> startedAt = new ConcurrentHashMap<>();

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
        runs.put(run.id(), run);
        Map<String, String> initialStates = new ConcurrentHashMap<>();
        orchestrationService.defaultGraph().forEach(stage -> initialStates.put(stage.id(), "PLANNED"));
        stageStates.put(run.id(), initialStates);
        auditEvents.put(run.id(), new java.util.concurrent.CopyOnWriteArrayList<>());
        record(run.id(), "RUN_CREATED", null, "requester", "Run created; assessment=" + assessment.clarity());
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
            boolean approved = "APPROVED".equals(state);
            return new ExecutionTrace(stage.id(), state, stage.description(), checks, approved);
        }).toList();
        int completed = (int) states.values().stream().filter(state -> "COMPLETED".equals(state) || "APPROVED".equals(state)).count();
        long latency = startedAt.containsKey(id) ? Duration.between(startedAt.get(id), run.updatedAt()).toMillis() : 0L;
        RuntimeMetrics metrics = orchestrationService.metricsFor(completed, graph.size(), 0, 0,
                (int) graph.stream().filter(StageDefinition::requiresHumanApproval).count(),
                run.status() == RunStatus.SAFE_STOPPED ? 1 : 0, Math.max(latency, 0));
        return new OrchestrationSnapshot(run.id(), run.requirement(), toOrchestrationStatus(run.status()),
                orchestrationService.buildPlanSummary(), traces, graph, metrics);
    }

    public EngineeringRun execute(UUID id) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.PLANNED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a planned run can be executed; current status is " + current.status());
        startedAt.put(id, Instant.now());
        List<String> graphIssues = orchestrationService.validationIssues();
        if (!graphIssues.isEmpty()) return safeStop(current, String.join("; ", graphIssues));

        Map<String, String> states = stageStates.get(id);
        // Iterate graph nodes only when all entry dependencies have passed their exit gates.
        for (StageDefinition stage : orchestrationService.defaultGraph()) {
            boolean dependenciesComplete = stage.dependencies().stream().allMatch(dep ->
                    "COMPLETED".equals(states.get(dep)) || "APPROVED".equals(states.get(dep)));
            if (!dependenciesComplete) return safeStop(current, "Entry gate failed for stage " + stage.id());
            states.put(stage.id(), "RUNNING");
            record(id, "STAGE_STARTED", stage.id(), "runtime", "Entry gate passed; dependencies=" + stage.dependencies());
            if (stage.id().equals("release-readiness")) {
                states.put(stage.id(), "WAITING_APPROVAL");
                record(id, "APPROVAL_REQUIRED", stage.id(), "runtime", "Release readiness requires explicit human approval.");
                continue;
            }
            states.put(stage.id(), "COMPLETED");
            record(id, "STAGE_COMPLETED", stage.id(), "runtime", "Exit gate passed.");
        }

        List<WorkItem> completed = current.workItems().stream().map(item -> new WorkItem(item.order(), item.kind(),
                item.title(), item.description(), WorkItemStatus.COMPLETED, outputFor(item.kind(), current))).toList();
        ValidationResult validation = validate(current.requirement(), completed, current.assessment());
        EngineeringRun executed = new EngineeringRun(current.id(), current.requirement(), RunStatus.REVIEW_REQUIRED,
                current.createdAt(), Instant.now(), completed, buildOutcome(current, completed, validation),
                validation, null, "", current.assessment());
        runs.put(id, executed);
        return executed;
    }

    public EngineeringRun review(UUID id, ReviewRequest request) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.REVIEW_REQUIRED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run must be awaiting review before a decision can be recorded");
        if (current.validation() == null || !current.validation().passed())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run cannot be reviewed because validation did not pass");
        if (request == null || request.decision() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review decision is required");
        if (request.decision() == ReviewDecision.APPROVE && current.assessment().clarity() == RequirementClarity.NEEDS_CLARIFICATION)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolve requirement clarification questions before approval");
        RunStatus status = request.decision() == ReviewDecision.APPROVE ? RunStatus.APPROVED : RunStatus.CHANGES_REQUESTED;
        Map<String, String> states = stageStates.get(id);
        states.put("release-readiness", request.decision() == ReviewDecision.APPROVE ? "APPROVED" : "CHANGES_REQUESTED");
        record(id, "HUMAN_REVIEW", "release-readiness", "requester", request.decision() + "; feedback=" + (request.feedback() == null ? "" : request.feedback().trim()));
        EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), status, current.createdAt(),
                Instant.now(), current.workItems(), current.outcome(), current.validation(), request.decision(),
                request.feedback() == null ? "" : request.feedback().trim(), current.assessment());
        runs.put(id, reviewed);
        return reviewed;
    }

    private EngineeringRun safeStop(EngineeringRun current, String reason) {
        stageStates.get(current.id()).replaceAll((stage, state) -> "PLANNED".equals(state) ? "BLOCKED" : state);
        record(current.id(), "SAFE_STOP", null, "runtime", reason);
        EngineeringRun stopped = new EngineeringRun(current.id(), current.requirement(), RunStatus.SAFE_STOPPED,
                current.createdAt(), Instant.now(), current.workItems(), "Execution stopped safely: " + reason,
                current.validation(), null, "", current.assessment());
        runs.put(current.id(), stopped);
        return stopped;
    }

    private void record(UUID runId, String type, String stage, String actor, String detail) {
        auditEvents.computeIfAbsent(runId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new RunAuditEvent(UUID.randomUUID(), runId, Instant.now(), type, stage, actor, detail));
    }

    private String outputFor(WorkItemKind kind, EngineeringRun run) {
        return switch (kind) {
            case REQUIREMENT_ANALYSIS -> "Scenario=" + run.assessment().scenario() + "; clarity=" + run.assessment().clarity() + "; questions=" + run.assessment().clarificationQuestions().size() + ".";
            case DECOMPOSITION -> switch (run.assessment().scenario()) {
                case GREENFIELD -> "Greenfield plan: design -> implementation -> tests and documentation (parallel) -> release readiness.";
                case BROWNFIELD -> run.assessment().suppliedCodebaseContext().isBlank()
                        ? "Brownfield selected without codebase context; impacted components remain unknown and require clarification."
                        : "Brownfield plan scoped only to caller-supplied context: " + run.assessment().suppliedCodebaseContext();
                case AUTO -> "Scenario classified during intake.";
            };
            case PROTOTYPE_EXECUTION -> "Bounded execution only. No source code, repository, or external system was changed.";
            case VALIDATION -> "Structural/reviewability validation only; acceptance correctness, security, and compliance were not executed.";
            case REVIEW_PACKAGE -> "Human-owned review required; no automatic approval is performed.";
        };
    }

    private ValidationResult validate(String requirement, List<WorkItem> items, RequirementAssessment assessment) {
        List<String> checks = new ArrayList<>(), issues = new ArrayList<>();
        check(!requirement.isBlank(), "Requirement is present", checks, issues);
        check(items.size() == PLAN.size(), "Expected workflow items are present", checks, issues);
        check(items.stream().allMatch(item -> item.status() == WorkItemStatus.COMPLETED), "All workflow items completed", checks, issues);
        check(items.stream().allMatch(item -> item.output() != null && !item.output().isBlank()), "Every item has an execution note", checks, issues);
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
        doc.append("\n## Plan and outputs\n\n");
        for (WorkItem item : items) doc.append(item.order()).append(". **").append(item.title()).append("** — ").append(item.output()).append("\n\n");
        doc.append("## Validation\n\n"); appendList(doc, validation.checks());
        doc.append("\n## Risks and limitations\n\n- This runtime executes a deterministic local graph model; it does not dispatch concurrent agents.\n")
                .append("- Run and audit state are in memory and are lost on restart; audit actors are not authenticated.\n")
                .append("- No code generation, repository inspection/mutation, CI, security/compliance scan, retry, or rollback is performed.\n")
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
