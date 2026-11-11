package com.hingera.agenticsdlc.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunServiceTest {

    private RunService service;

    @BeforeEach
    void setUp() {
        service = new RunService();
    }

    @Test
    void createsPlansAndExecutesIntoReviewableValidatedOutcome() {
        EngineeringRun planned = service.create("Build a working prototype for controlled SDLC automation.");

        assertEquals(RunStatus.PLANNED, planned.status());
        assertEquals(5, planned.workItems().size());
        assertTrue(planned.workItems().stream().allMatch(item -> item.status() == WorkItemStatus.PLANNED));

        EngineeringRun executed = service.execute(planned.id());

        assertEquals(RunStatus.REVIEW_REQUIRED, executed.status());
        assertTrue(executed.validation().passed());
        assertNotNull(executed.outcome());
        assertTrue(executed.outcome().contains("Submitted requirement"));
        assertTrue(executed.outcome().contains("human must inspect"));
    }

    @Test
    void requiresAnExplicitHumanDecisionAfterExecution() {
        EngineeringRun planned = service.create("Build a reviewable engineering outcome prototype.");
        EngineeringRun executed = service.execute(planned.id());

        EngineeringRun reviewed = service.review(executed.id(),
                new ReviewRequest(ReviewDecision.APPROVE, "Looks good"));

        assertEquals(RunStatus.APPROVED, reviewed.status());
        assertEquals(ReviewDecision.APPROVE, reviewed.reviewDecision());
        assertEquals("Looks good", reviewed.reviewFeedback());
    }

    @Test
    void rejectsExecutionMoreThanOnce() {
        EngineeringRun planned = service.create("Build an agentic SDLC automation prototype.");
        service.execute(planned.id());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.execute(planned.id()));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void recordsRequestChangesAsAReviewOutcome() {
        EngineeringRun planned = service.create("Generate a validated software engineering plan.");
        EngineeringRun executed = service.execute(planned.id());

        EngineeringRun reviewed = service.review(executed.id(),
                new ReviewRequest(ReviewDecision.REQUEST_CHANGES, "Add acceptance tests"));

        assertEquals(RunStatus.CHANGES_REQUESTED, reviewed.status());
        assertFalse(reviewed.reviewFeedback().isBlank());
    }

    @Test
    void exposesAnOrchestrationSnapshot() {
        EngineeringRun planned = service.create("Build a reviewable engineering outcome with orchestration metadata.");

        OrchestrationSnapshot snapshot = service.orchestrationSnapshot(planned.id());

        assertNotNull(snapshot);
        assertEquals(6, snapshot.graph().size());
        assertTrue(snapshot.summary().contains("Governance-aware orchestration graph"));
        assertEquals(6, snapshot.traces().size());
        assertTrue(snapshot.metrics().totalStages() > 0);
    }
}
