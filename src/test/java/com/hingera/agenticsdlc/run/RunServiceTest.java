package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.RunOrchestrationService;
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

    @Test void graphIncludesParallelBranchesAndReleaseSynchronization() {
        RunOrchestrationService graph = new RunOrchestrationService();
        var stages = graph.defaultGraph();
        var testing = stages.stream().filter(s -> s.id().equals("testing")).findFirst().orElseThrow();
        var docs = stages.stream().filter(s -> s.id().equals("documentation")).findFirst().orElseThrow();
        var release = stages.stream().filter(s -> s.id().equals("release-readiness")).findFirst().orElseThrow();
        assertEquals(testing.dependencies(), docs.dependencies());
        assertTrue(release.dependencies().containsAll(List.of("testing", "documentation")));
        assertTrue(graph.validationIssues().isEmpty());
    }

    @Test void graphExecutionRecordsOrderedStageTransitionsAndHumanApproval() {
        EngineeringRun planned = service.create(new CreateRunRequest("Build a working SDLC planning prototype.", ProjectScenario.GREENFIELD, "", List.of("Produces a review artifact")));
        EngineeringRun executed = service.execute(planned.id());
        assertEquals(RunStatus.REVIEW_REQUIRED, executed.status());
        OrchestrationSnapshot snapshot = service.orchestrationSnapshot(planned.id());
        assertEquals(7, snapshot.graph().size());
        assertEquals("WAITING_APPROVAL", snapshot.traces().getLast().status());
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("STAGE_COMPLETED")));
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("APPROVAL_REQUIRED")));
    }

    @Test void detectsAmbiguityAndBlocksApproval() {
        EngineeringRun planned = service.create(new CreateRunRequest("Build a better SDLC system for our team.", ProjectScenario.AUTO, "", List.of()));
        assertEquals(RequirementClarity.NEEDS_CLARIFICATION, planned.assessment().clarity());
        EngineeringRun executed = service.execute(planned.id());
        assertFalse(executed.validation().passed());
        assertThrows(ResponseStatusException.class, () -> service.review(executed.id(), new ReviewRequest(ReviewDecision.APPROVE, "approve")));
    }

    @Test void brownfieldContextIsIncludedAndHumanDecisionRecorded() {
        EngineeringRun planned = service.create(new CreateRunRequest("Refactor the existing billing service to fix rounding.", ProjectScenario.BROWNFIELD, "billing module; invoice API", List.of("Totals follow currency rules")));
        EngineeringRun executed = service.execute(planned.id());
        assertTrue(executed.outcome().contains("billing module; invoice API"));
        assertNotNull(executed.outcome());
        EngineeringRun approved = service.review(executed.id(), new ReviewRequest(ReviewDecision.APPROVE, "Reviewed"));
        assertEquals(RunStatus.APPROVED, approved.status());
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("HUMAN_REVIEW")));
    }
}
