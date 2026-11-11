package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.ExecutionTrace;
import com.hingera.agenticsdlc.orchestration.OrchestrationStatus;
import com.hingera.agenticsdlc.orchestration.RunOrchestrationService;
import com.hingera.agenticsdlc.orchestration.RuntimeMetrics;
import com.hingera.agenticsdlc.orchestration.StageDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public EngineeringRun create(String rawRequirement) {
        return create(new CreateRunRequest(rawRequirement));
    }

    public EngineeringRun create(CreateRunRequest request) {
        if (request == null || request.requirement() == null || request.requirement().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requirement cannot be blank");
        }
        String requirement = request.requirement().trim();
        RequirementAssessment assessment = understandingService.assess(requirement, request.scenario(),
                request.codebaseContext(), request.acceptanceCriteria());
        Instant now = Instant.now();
        List<WorkItem> workItems = new ArrayList<>();
        for (int i = 0; i < PLAN.size(); i++) {
            WorkItemDefinition item = PLAN.get(i);
            workItems.add(new WorkItem(i + 1, item.kind(), item.title(), item.description(), WorkItemStatus.PLANNED, ""));
        }
        EngineeringRun run = new EngineeringRun(UUID.randomUUID(), requirement, RunStatus.PLANNED,
                now, now, workItems, "", null, null, "", assessment);
        runs.put(run.id(), run);
        return run;
    }

    public List<EngineeringRun> findAll() {
        return runs.values().stream().sorted(Comparator.comparing(EngineeringRun::createdAt)).toList();
    }

    public EngineeringRun findById(UUID id) {
        EngineeringRun run = runs.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + id);
        return run;
    }

    public OrchestrationSnapshot orchestrationSnapshot(UUID id) {
        EngineeringRun run = findById(id);
        List<StageDefinition> graph = orchestrationService.defaultGraph();
        List<ExecutionTrace> traces = new ArrayList<>();
        int completed = run.status() == RunStatus.PLANNED ? 0 : run.workItems().size();
        for (int i = 0; i < graph.size(); i++) {
            StageDefinition stage = graph.get(i);
            String state = run.status() == RunStatus.PLANNED ? "PLANNED"
                    : run.status() == RunStatus.REVIEW_REQUIRED && stage.requiresHumanApproval() ? "WAITING_APPROVAL" : "COMPLETED";
            traces.add(new ExecutionTrace(stage.id(), state, stage.description(),
                    stage.dependencies().stream().map(dep -> "Depends on: " + dep).toList(),
                    run.status() == RunStatus.APPROVED && stage.requiresHumanApproval()));
        }
        RuntimeMetrics metrics = orchestrationService.metricsFor(Math.min(completed, graph.size()), graph.size(), 0, 0,
                (int) graph.stream().filter(StageDefinition::requiresHumanApproval).count(), 0, 0L);
        return new OrchestrationSnapshot(run.id(), run.requirement(), toOrchestrationStatus(run.status()),
                orchestrationService.buildPlanSummary(), traces, graph, metrics);
    }

    public EngineeringRun execute(UUID id) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a planned run can be executed; current status is " + current.status());
        }
        List<String> graphIssues = orchestrationService.validationIssues();
        if (!graphIssues.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Execution safe-stopped: invalid orchestration graph: " + String.join("; ", graphIssues));
        }
        List<WorkItem> completed = new ArrayList<>();
        for (WorkItem item : current.workItems()) {
            completed.add(new WorkItem(item.order(), item.kind(), item.title(), item.description(),
                    WorkItemStatus.COMPLETED, outputFor(item.kind(), current)));
        }
        ValidationResult validation = validate(current.requirement(), completed, current.assessment());
        EngineeringRun executed = new EngineeringRun(current.id(), current.requirement(), RunStatus.REVIEW_REQUIRED,
                current.createdAt(), Instant.now(), completed, buildOutcome(current, completed, validation),
                validation, null, "", current.assessment());
        runs.put(id, executed);
        return executed;
    }

    public EngineeringRun review(UUID id, ReviewRequest request) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.REVIEW_REQUIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run must be awaiting review before a decision can be recorded");
        }
        if (current.validation() == null || !current.validation().passed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run cannot be reviewed because validation did not pass");
        }
        if (request == null || request.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review decision is required");
        }
        if (request.decision() == ReviewDecision.APPROVE && current.assessment().clarity() == RequirementClarity.NEEDS_CLARIFICATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolve requirement clarification questions before approval");
        }
        RunStatus status = request.decision() == ReviewDecision.APPROVE ? RunStatus.APPROVED : RunStatus.CHANGES_REQUESTED;
        EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), status, current.createdAt(),
                Instant.now(), current.workItems(), current.outcome(), current.validation(), request.decision(),
                request.feedback() == null ? "" : request.feedback().trim(), current.assessment());
        runs.put(id, reviewed);
        return reviewed;
    }

    private String outputFor(WorkItemKind kind, EngineeringRun run) {
        return switch (kind) {
            case REQUIREMENT_ANALYSIS -> "Scenario=" + run.assessment().scenario() + "; clarity=" + run.assessment().clarity()
                    + "; clarification questions=" + run.assessment().clarificationQuestions().size() + ".";
            case DECOMPOSITION -> switch (run.assessment().scenario()) {
                case GREENFIELD -> "Greenfield plan: define architecture, interfaces, implementation, tests, and docs; dependencies require design before implementation and tests before release readiness.";
                case BROWNFIELD -> run.assessment().suppliedCodebaseContext().isBlank()
                        ? "Brownfield selected, but no repository context supplied; impacted modules/APIs/data flows remain unknown and must be provided."
                        : "Brownfield plan scoped only to user-supplied codebase context: " + run.assessment().suppliedCodebaseContext();
                case AUTO -> "Scenario should have been classified during intake.";
            };
            case PROTOTYPE_EXECUTION -> "Proposed engineering approach only. No source code, repository, or external system was changed.";
            case VALIDATION -> "Structural/reviewability validation only; acceptance correctness, security, and compliance were not executed.";
            case REVIEW_PACKAGE -> "Human-owned review required; no automatic approval is performed.";
        };
    }

    private ValidationResult validate(String requirement, List<WorkItem> items, RequirementAssessment assessment) {
        List<String> checks = new ArrayList<>();
        List<String> issues = new ArrayList<>();
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
                .append(run.requirement()).append("\n\n## Requirement assessment\n\n")
                .append("Scenario: ").append(run.assessment().scenario()).append("\n\nClarity: ")
                .append(run.assessment().clarity()).append("\n\n## Ambiguity and questions\n\n");
        appendList(doc, run.assessment().ambiguityReasons());
        appendList(doc, run.assessment().clarificationQuestions());
        doc.append("\n## Assumptions\n\n"); appendList(doc, run.assessment().assumptions());
        doc.append("\n## Acceptance criteria supplied\n\n"); appendList(doc, run.assessment().acceptanceCriteria());
        doc.append("\n## Supplied codebase context\n\n").append(run.assessment().suppliedCodebaseContext().isBlank()
                ? "None. No repository inspection or impacted-module discovery is performed by this prototype."
                : run.assessment().suppliedCodebaseContext()).append("\n\n## Work items\n\n");
        for (WorkItem item : items) doc.append(item.order()).append(". **").append(item.title()).append("** — ")
                .append(item.status()).append(": ").append(item.output()).append("\n\n");
        doc.append("## Validation\n\n"); appendList(doc, validation.checks());
        doc.append("\n## Risks and limitations\n\n- Requirement analysis is deterministic heuristic triage, not semantic agent reasoning.\n")
                .append("- No code generation, repository inspection/mutation, CI execution, security scan, or compliance check occurs.\n")
                .append("- Validation checks structure and clarity only; a human owns final quality and approval.\n");
        return doc.toString();
    }

    private void appendList(StringBuilder doc, List<String> values) {
        if (values.isEmpty()) doc.append("- None recorded.\n");
        else values.forEach(value -> doc.append("- ").append(value).append('\n'));
    }

    private OrchestrationStatus toOrchestrationStatus(RunStatus status) {
        return switch (status) {
            case PLANNED -> OrchestrationStatus.PLANNED;
            case REVIEW_REQUIRED -> OrchestrationStatus.WAITING_APPROVAL;
            case APPROVED -> OrchestrationStatus.APPROVED;
            case CHANGES_REQUESTED -> OrchestrationStatus.CHANGES_REQUESTED;
        };
    }

    private record WorkItemDefinition(WorkItemKind kind, String title, String description) { }
}
