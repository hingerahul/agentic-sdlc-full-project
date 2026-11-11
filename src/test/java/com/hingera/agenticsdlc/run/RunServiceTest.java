package com.hingera.agenticsdlc.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunServiceTest {
    private RunService service;

    @BeforeEach void setUp() { service = new RunService(); }

    @Test void createsAndExecutesReviewableValidatedOutcome() {
        EngineeringRun planned = service.create("Build a working prototype for controlled SDLC automation.");
        assertEquals(RunStatus.PLANNED, planned.status());
        assertEquals(ProjectScenario.GREENFIELD, planned.assessment().scenario());
        assertEquals(5, planned.workItems().size());
        EngineeringRun executed = service.execute(planned.id());
        assertEquals(RunStatus.REVIEW_REQUIRED, executed.status());
        assertTrue(executed.validation().passed());
        assertTrue(executed.outcome().contains("Requirement assessment"));
        assertTrue(executed.outcome().contains("human owns final quality"));
    }

    @Test void detectsAmbiguityAndBlocksApprovalUntilClarified() {
        EngineeringRun planned = service.create(new CreateRunRequest("Build a better SDLC system for our team.",
                ProjectScenario.AUTO, "", List.of()));
        assertEquals(RequirementClarity.NEEDS_CLARIFICATION, planned.assessment().clarity());
        assertFalse(planned.assessment().clarificationQuestions().isEmpty());
        EngineeringRun executed = service.execute(planned.id());
        assertFalse(executed.validation().passed());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.review(executed.id(), new ReviewRequest(ReviewDecision.APPROVE, "approve")));
        assertEquals(409, error.getStatusCode().value());
    }

    @Test void brownfieldScenarioRequiresAndPreservesSuppliedContext() {
        EngineeringRun run = service.create(new CreateRunRequest("Refactor the existing billing service to fix rounding.",
                ProjectScenario.BROWNFIELD, "billing module; invoice API; payment reconciliation flow", List.of("Totals match currency rounding rules")));
        assertEquals(ProjectScenario.BROWNFIELD, run.assessment().scenario());
        assertEquals(RequirementClarity.CLEAR, run.assessment().clarity());
        EngineeringRun executed = service.execute(run.id());
        assertTrue(executed.outcome().contains("billing module; invoice API"));
        assertTrue(executed.outcome().contains("No code generation"));
    }

    @Test void requiresExplicitHumanDecisionAfterExecution() {
        EngineeringRun run = service.execute(service.create(new CreateRunRequest(
                "Add an API endpoint for listing saved reports.", ProjectScenario.GREENFIELD, "",
                List.of("GET /reports returns the caller's reports"))).id());
        EngineeringRun reviewed = service.review(run.id(), new ReviewRequest(ReviewDecision.APPROVE, "Reviewed"));
        assertEquals(RunStatus.APPROVED, reviewed.status());
        assertEquals(ReviewDecision.APPROVE, reviewed.reviewDecision());
    }

    @Test void rejectsRepeatedExecutionAndRecordsRequestChanges() {
        EngineeringRun run = service.create(new CreateRunRequest("Generate a validated software engineering plan.",
                ProjectScenario.GREENFIELD, "", List.of("Plan contains test and release steps")));
        service.execute(run.id());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.execute(run.id()));
        assertEquals(409, error.getStatusCode().value());
        EngineeringRun requestedChanges = service.review(run.id(), new ReviewRequest(ReviewDecision.REQUEST_CHANGES, "Add acceptance tests"));
        assertEquals(RunStatus.CHANGES_REQUESTED, requestedChanges.status());
        assertFalse(requestedChanges.reviewFeedback().isBlank());
    }

    @Test void exposesOrchestrationSnapshot() {
        OrchestrationSnapshot snapshot = service.orchestrationSnapshot(service.create("Build a reviewable prototype with orchestration metadata.").id());
        assertNotNull(snapshot);
        assertEquals(6, snapshot.graph().size());
        assertTrue(snapshot.summary().contains("Governance-aware orchestration graph"));
        assertEquals(6, snapshot.traces().size());
    }
}
