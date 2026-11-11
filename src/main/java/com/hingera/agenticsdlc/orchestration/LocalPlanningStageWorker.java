package com.hingera.agenticsdlc.orchestration;

import com.hingera.agenticsdlc.run.EngineeringRun;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LocalPlanningStageWorker implements StageWorker {
    @Override
    public String execute(StageDefinition stage, EngineeringRun run, Map<String, String> priorOutputs) {
        return switch (stage.id()) {
            case "requirement-analysis" -> requirementAnalysis(run);
            case "codebase-analysis" -> codebaseAnalysis(run);
            case "architecture-design" -> architectureDesign(run, priorOutputs);
            case "implementation" -> implementationPlan(run, priorOutputs);
            case "testing" -> testingPlan(run, priorOutputs);
            case "documentation" -> documentationPlan(run, priorOutputs);
            case "release-readiness" -> releaseReadiness(run, priorOutputs);
            default -> throw new IllegalArgumentException("Unsupported planning stage: " + stage.id());
        };
    }

    private String requirementAnalysis(EngineeringRun run) {
        var assessment = run.assessment();
        return """
                ## Normalized requirement
                %s

                Scenario: %s
                Clarity: %s
                ## Acceptance criteria
                %s
                ## Open questions
                %s
                """.formatted(run.requirement(), assessment.scenario(), assessment.clarity(),
                bullets(assessment.acceptanceCriteria()), bullets(assessment.clarificationQuestions())).trim();
    }

    private String codebaseAnalysis(EngineeringRun run) {
        String context = run.assessment().suppliedCodebaseContext();
        if (context.isBlank()) {
            return "## Brownfield context\nNo codebase context was supplied. Impacted modules, APIs, and data flows are unknown; repository inspection is not performed.";
        }
        return """
                ## Supplied codebase scope
                %s

                Impact analysis is limited to caller-provided context. Repository contents, callers, and data flows were not independently inspected.
                """.formatted(context).trim();
    }

    private String architectureDesign(EngineeringRun run, Map<String, String> priorOutputs) {
        return """
                ## Design approach
                Treat the submitted requirement and acceptance criteria as the source of scope. Keep changes within the supplied scenario/context and separate implementation from verification and documentation.

                ## Inputs considered
                %s

                ## Decisions and risks
                - No repository-wide facts are inferred beyond supplied context.
                - Validate each acceptance criterion before considering release.
                - Obtain human approval before implementation work.
                """.formatted(upstream(priorOutputs, "requirement-analysis", "codebase-analysis")).trim();
    }

    private String implementationPlan(EngineeringRun run, Map<String, String> priorOutputs) {
        List<String> criteria = run.assessment().acceptanceCriteria();
        String tasks = criteria.isEmpty()
                ? "- Resolve clarification questions and obtain measurable acceptance criteria before implementation."
                : java.util.stream.IntStream.range(0, criteria.size())
                .mapToObj(index -> "- Task %d: implement behavior required by criterion: %s".formatted(index + 1, criteria.get(index)))
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                ## Ordered implementation tasks
                1. Confirm design and supplied codebase scope with the reviewer.
                %s
                2. Add or update automated coverage for each criterion.
                3. Run relevant checks and document results before release review.

                ## Dependency and scope notes
                Tasks depend on architecture approval. This is a plan only; source files are not changed.
                %s
                """.formatted(tasks, upstream(priorOutputs, "architecture-design")).trim();
    }

    private String testingPlan(EngineeringRun run, Map<String, String> priorOutputs) {
        List<String> criteria = run.assessment().acceptanceCriteria();
        String cases = criteria.isEmpty() ? "- No criteria were supplied; test design is blocked pending clarification."
                : java.util.stream.IntStream.range(0, criteria.size())
                .mapToObj(index -> "- Test %d: verify criterion: %s".formatted(index + 1, criteria.get(index)))
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                ## Verification plan
                %s
                - Add regression coverage for existing behavior affected by the change.
                - Run focused tests, then the relevant integration suite.
                - Record actual commands, results, and remaining gaps; no target tests ran in this prototype.

                ## Inputs considered
                %s
                """.formatted(cases, upstream(priorOutputs, "implementation")).trim();
    }

    private String documentationPlan(EngineeringRun run, Map<String, String> priorOutputs) {
        return """
                ## Documentation deliverables
                - Explain the user-visible behavior and any compatibility impact.
                - Document configuration, operational steps, and recovery guidance where applicable.
                - Link each acceptance criterion to implementation and verification evidence.
                - State unverified assumptions and limitations explicitly.

                ## Inputs considered
                %s
                """.formatted(upstream(priorOutputs, "implementation")).trim();
    }

    private String releaseReadiness(EngineeringRun run, Map<String, String> priorOutputs) {
        return """
                ## Release checklist
                - Confirm all acceptance criteria have evidence from the target project's tests.
                - Review security, compliance, operational, and rollback risks with accountable owners.
                - Confirm documentation and change approvals are complete.
                - Obtain final human approval; this prototype cannot deploy or attest to target-system correctness.

                ## Branch evidence available
                %s
                """.formatted(upstream(priorOutputs, "testing", "documentation")).trim();
    }

    private String upstream(Map<String, String> outputs, String... stageIds) {
        return java.util.Arrays.stream(stageIds)
                .map(id -> "- " + id + ": " + (outputs.containsKey(id) ? "completed output is available" : "no output available"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String bullets(List<String> values) {
        return values.isEmpty() ? "- None supplied." : values.stream().map(value -> "- " + value)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
