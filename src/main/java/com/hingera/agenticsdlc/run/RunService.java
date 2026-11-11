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

    private final RunOrchestrationService orchestrationService = new RunOrchestrationService();

    private static final List<WorkItemDefinition> PLAN = List.of(
            new WorkItemDefinition(WorkItemKind.REQUIREMENT_ANALYSIS, "Understand requirement",
                    "Capture the goal and preserve the submitted requirement as the source of truth."),
            new WorkItemDefinition(WorkItemKind.DECOMPOSITION, "Decompose into engineering steps",
                    "Create an ordered, reviewable sequence covering design, implementation, and verification."),
            new WorkItemDefinition(WorkItemKind.PROTOTYPE_EXECUTION, "Execute bounded prototype",
                    "Generate a proposed engineering approach without modifying repositories or calling external systems."),
            new WorkItemDefinition(WorkItemKind.VALIDATION, "Validate generated outcome",
                    "Check that the run has a requirement, completed work items, and a non-empty review artifact."),
            new WorkItemDefinition(WorkItemKind.REVIEW_PACKAGE, "Prepare human review package",
                    "Present the work plan, execution notes, validation results, and explicit review gate.")
    );

    private final ConcurrentMap<UUID, EngineeringRun> runs = new ConcurrentHashMap<>();

    public EngineeringRun create(String rawRequirement) {
        String requirement = rawRequirement.trim();
        Instant now = Instant.now();
        List<WorkItem> workItems = PLAN.stream()
                .map(item -> new WorkItem(PLAN.indexOf(item) + 1, item.kind(), item.title(),
                        item.description(), WorkItemStatus.PLANNED, ""))
                .toList();
        EngineeringRun run = new EngineeringRun(UUID.randomUUID(), requirement, RunStatus.PLANNED,
                now, now, workItems, "", null, null, "");
        runs.put(run.id(), run);
        return run;
    }

    public List<EngineeringRun> findAll() {
        return runs.values().stream()
                .sorted(Comparator.comparing(EngineeringRun::createdAt))
                .toList();
    }

    public EngineeringRun findById(UUID id) {
        EngineeringRun run = runs.get(id);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + id);
        }
        return run;
    }

    public OrchestrationSnapshot orchestrationSnapshot(UUID id) {
        EngineeringRun run = findById(id);
        List<StageDefinition> graph = orchestrationService.defaultGraph();
        List<ExecutionTrace> traces = new ArrayList<>();
        int completedStages = 0;

        for (StageDefinition stage : graph) {
            boolean isCompleted = run.status() != RunStatus.PLANNED &&
                    (run.status() == RunStatus.APPROVED || run.status() == RunStatus.CHANGES_REQUESTED || run.status() == RunStatus.REVIEW_REQUIRED ||
                            stage.dependencies().isEmpty() || run.workItems().stream().anyMatch(item -> item.status() == WorkItemStatus.COMPLETED));
            if (isCompleted) {
                completedStages++;
            }
            traces.add(new ExecutionTrace(
                    stage.id(),
                    isCompleted ? "COMPLETED" : (stage.requiresHumanApproval() ? "WAITING_APPROVAL" : "PLANNED"),
                    stage.description(),
                    stage.dependencies().isEmpty() ? List.of("Requirement captured and normalized") : stage.dependencies().stream()
                            .map(dep -> "Dependency satisfied: " + dep)
                            .toList(),
                    stage.requiresHumanApproval() && run.status() == RunStatus.REVIEW_REQUIRED
            ));
        }

        RuntimeMetrics metrics = orchestrationService.metricsFor(
                completedStages,
                graph.size(),
                0,
                0,
                (int) graph.stream().filter(StageDefinition::requiresHumanApproval).count(),
                0,
                0L
        );

        return new OrchestrationSnapshot(
                run.id(),
                run.requirement(),
                toOrchestrationStatus(run.status()),
                orchestrationService.buildPlanSummary(),
                traces,
                graph,
                metrics
        );
    }

    public EngineeringRun execute(UUID id) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.PLANNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a planned run can be executed; current status is " + current.status());
        }

        List<WorkItem> completed = new ArrayList<>();
        for (WorkItem item : current.workItems()) {
            completed.add(new WorkItem(item.order(), item.kind(), item.title(), item.description(),
                    WorkItemStatus.COMPLETED, outputFor(item.kind(), current.requirement())));
        }

        ValidationResult validation = validate(current.requirement(), completed);
        String outcome = buildOutcome(current.requirement(), completed, validation);
        EngineeringRun executed = new EngineeringRun(current.id(), current.requirement(),
                RunStatus.REVIEW_REQUIRED, current.createdAt(), Instant.now(), completed,
                outcome, validation, null, "");
        runs.put(id, executed);
        return executed;
    }

    public EngineeringRun review(UUID id, ReviewRequest request) {
        EngineeringRun current = findById(id);
        if (current.status() != RunStatus.REVIEW_REQUIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run must be awaiting review before a decision can be recorded");
        }
        if (current.validation() == null || !current.validation().passed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run cannot be reviewed because validation did not pass");
        }

        String feedback = request.feedback() == null ? "" : request.feedback().trim();
        RunStatus status = request.decision() == ReviewDecision.APPROVE
                ? RunStatus.APPROVED : RunStatus.CHANGES_REQUESTED;
        EngineeringRun reviewed = new EngineeringRun(current.id(), current.requirement(), status,
                current.createdAt(), Instant.now(), current.workItems(), current.outcome(),
                current.validation(), request.decision(), feedback);
        runs.put(id, reviewed);
        return reviewed;
    }

    private OrchestrationStatus toOrchestrationStatus(RunStatus runStatus) {
        return switch (runStatus) {
            case PLANNED -> OrchestrationStatus.PLANNED;
            case REVIEW_REQUIRED -> OrchestrationStatus.WAITING_APPROVAL;
            case APPROVED -> OrchestrationStatus.APPROVED;
            case CHANGES_REQUESTED -> OrchestrationStatus.CHANGES_REQUESTED;
        };
    }

    private String outputFor(WorkItemKind kind, String requirement) {
        return switch (kind) {
            case REQUIREMENT_ANALYSIS -> "Requirement captured (" + requirement.length()
                    + " characters). No assumptions were added to the source requirement.";
            case DECOMPOSITION -> "Five ordered workflow steps created: understand, decompose, execute, validate, and review.";
            case PROTOTYPE_EXECUTION -> "Prototype execution is rules-based and produces this review artifact only; no code, repository, or external service was changed.";
            case VALIDATION -> "Structural validation checks are run and their results are attached to this run.";
            case REVIEW_PACKAGE -> "Outcome document assembled. Human review is required before this run can be approved.";
        };
    }

    private ValidationResult validate(String requirement, List<WorkItem> workItems) {
        List<String> checks = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        check(!requirement.isBlank(), "Requirement is present", checks, issues);
        check(workItems.size() == PLAN.size(), "Expected workflow steps are present", checks, issues);
        check(workItems.stream().allMatch(item -> item.status() == WorkItemStatus.COMPLETED),
                "All workflow steps completed", checks, issues);
        check(workItems.stream().allMatch(item -> !item.output().isBlank()),
                "Every workflow step has an execution note", checks, issues);
        check(!workItems.isEmpty(), "Review artifact has source material", checks, issues);
        return new ValidationResult(issues.isEmpty(), checks, issues);
    }

    private void check(boolean passed, String label, List<String> checks, List<String> issues) {
        checks.add((passed ? "PASS: " : "FAIL: ") + label);
        if (!passed) {
            issues.add(label);
        }
    }

    private String buildOutcome(String requirement, List<WorkItem> workItems, ValidationResult validation) {
        StringBuilder document = new StringBuilder("# Engineering outcome\n\n")
                .append("## Submitted requirement\n\n")
                .append(requirement).append("\n\n")
                .append("## Decomposed plan and execution notes\n\n");
        for (WorkItem item : workItems) {
            document.append(item.order()).append(". **").append(item.title()).append("** — ")
                    .append(item.description()).append("\n\n   Status: ").append(item.status())
                    .append(". ").append(item.output()).append("\n\n");
        }
        document.append("## Validation\n\n");
        validation.checks().forEach(check -> document.append("- ").append(check).append('\n'));
        document.append("\n## Autonomy and review boundary\n\n")
                .append("This prototype uses deterministic local workflow steps. It does not generate or apply source-code changes, access external services, or claim that the requirement is implemented.\n\n")
                .append("A human must inspect this outcome and explicitly approve it or request changes through the review endpoint.\n");
        return document.toString();
    }

    private record WorkItemDefinition(WorkItemKind kind, String title, String description) {
    }
}
