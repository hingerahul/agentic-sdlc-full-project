package com.hingera.agenticsdlc.run;

import com.hingera.agenticsdlc.orchestration.RunOrchestrationService;
import com.hingera.agenticsdlc.orchestration.StageWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("architecture-design", service.auditTrail(planned.id()).stream()
                .filter(event -> event.eventType().equals("APPROVAL_REQUIRED")).findFirst().orElseThrow().stageId());
        executed = service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));
        assertEquals("implementation", service.auditTrail(planned.id()).stream()
                .filter(event -> event.eventType().equals("APPROVAL_REQUIRED")).reduce((first, second) -> second).orElseThrow().stageId());
        executed = service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Implementation approved"));
        assertEquals(RunStatus.REVIEW_REQUIRED, executed.status());
        OrchestrationSnapshot snapshot = service.orchestrationSnapshot(planned.id());
        assertEquals(7, snapshot.graph().size());
        assertEquals(14, snapshot.gates().size());
        assertEquals("WAITING_APPROVAL", snapshot.traces().getLast().status());
        assertTrue(executed.outcome().contains("Test 1: verify criterion: Produces a review artifact"));
        assertTrue(executed.outcome().contains("no target tests ran in this prototype"));
        assertTrue(executed.outcome().contains("final human approval"));
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("STAGE_COMPLETED")));
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("APPROVAL_REQUIRED")));
    }

    @Test void detectsAmbiguityAndBlocksApproval() {
        EngineeringRun planned = service.create(new CreateRunRequest("Build a better SDLC system for our team.", ProjectScenario.AUTO, "", List.of()));
        assertEquals(RequirementClarity.NEEDS_CLARIFICATION, planned.assessment().clarity());
        EngineeringRun executed = service.execute(planned.id());
        assertEquals(RunStatus.REVIEW_REQUIRED, executed.status());
        assertThrows(ResponseStatusException.class, () -> service.review(executed.id(), new ReviewRequest(ReviewDecision.APPROVE, "approve")));
    }

    @Test void brownfieldContextIsIncludedAndHumanDecisionRecorded() {
        EngineeringRun planned = service.create(new CreateRunRequest("Refactor the existing billing service to fix rounding.", ProjectScenario.BROWNFIELD, "billing module; invoice API", List.of("Totals follow currency rules")));
        EngineeringRun executed = service.execute(planned.id());
        executed = service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));
        executed = service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Implementation approved"));
        assertTrue(executed.outcome().contains("billing module; invoice API"));
        assertTrue(executed.outcome().contains("Repository contents, callers, and data flows were not independently inspected"));
        assertTrue(executed.outcome().contains("Test 1: verify criterion: Totals follow currency rules"));
        assertNotNull(executed.outcome());
        assertTrue(executed.validation().passed());
        EngineeringRun approved = service.review(executed.id(), new ReviewRequest(ReviewDecision.APPROVE, "Reviewed"));
        assertEquals(RunStatus.APPROVED, approved.status());
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("HUMAN_REVIEW")));
    }

    @Test void replanResetsWorkflowStateAndPreservesDecisionLineage() {
        EngineeringRun planned = service.create(new CreateRunRequest(
                "Refactor billing totals and preserve currency rounding.", ProjectScenario.BROWNFIELD,
                "billing API", List.of("Totals use currency rounding")));
        service.execute(planned.id());
        service.review(planned.id(), new ReviewRequest(ReviewDecision.REQUEST_CHANGES, "Revise the scope"));

        EngineeringRun replanned = service.replan(planned.id(), new CreateRunRequest(
                "Refactor billing totals and preserve currency rounding.", ProjectScenario.BROWNFIELD,
                "billing API and reconciliation flow", List.of("Totals use currency rounding")));

        assertEquals(RunStatus.PLANNED, replanned.status());
        assertTrue(service.orchestrationSnapshot(planned.id()).traces().stream().allMatch(trace -> trace.status().equals("PLANNED")));
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("RUN_REPLANNED")));
        service.execute(planned.id());
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Implementation approved"));
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Release approved"));
        assertThrows(ResponseStatusException.class, () -> service.replan(planned.id(), new CreateRunRequest(
                "A valid follow-up requirement with acceptance criteria.", ProjectScenario.GREENFIELD, "",
                List.of("A testable outcome"))));
    }

    @Test void revisedHighImpactOutputRequiresRenewedApprovalBeforeDependents() {
        EngineeringRun planned = service.create(new CreateRunRequest(
                "Build a workflow with measurable acceptance criteria.", ProjectScenario.GREENFIELD, "",
                List.of("Produces a review artifact")));
        service.execute(planned.id());
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Initial architecture approved"));

        EngineeringRun awaitingImplementationApproval = service.findById(planned.id());
        assertEquals(RunStatus.REVIEW_REQUIRED, awaitingImplementationApproval.status());
        EngineeringRun revised = service.reviseStageOutput(planned.id(), "architecture-design",
                new StageOutputRevision("Revised architecture with an additional persistence boundary.",
                        "A new storage dependency was identified."));

        assertEquals(RunStatus.REVIEW_REQUIRED, revised.status());
        assertEquals("WAITING_APPROVAL", service.orchestrationSnapshot(planned.id()).traces().get(2).status());
        assertEquals("PLANNED", service.orchestrationSnapshot(planned.id()).traces().get(3).status());
        assertTrue(service.auditTrail(planned.id()).stream()
                .anyMatch(event -> event.eventType().equals("UPSTREAM_OUTPUT_REVISED")));

        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Revised architecture approved"));
        assertEquals("implementation", service.auditTrail(planned.id()).stream()
                .filter(event -> event.eventType().equals("APPROVAL_REQUIRED"))
                .reduce((first, second) -> second).orElseThrow().stageId());
    }

    @Test void executesIndependentStagesConcurrentlyAndRetriesTransientFailure() throws Exception {
        AtomicInteger activeBranches = new AtomicInteger();
        AtomicInteger peakBranches = new AtomicInteger();
        AtomicInteger testingAttempts = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean receivedUpstreamContext = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch bothBranchesStarted = new CountDownLatch(2);
        StageWorker worker = (stage, run, priorOutputs) -> {
            if (List.of("testing", "documentation").contains(stage.id())) {
                if (priorOutputs.containsKey("implementation")) receivedUpstreamContext.set(true);
                peakBranches.accumulateAndGet(activeBranches.incrementAndGet(), Math::max);
                bothBranchesStarted.countDown();
                try {
                    if (!bothBranchesStarted.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("Independent stage did not start concurrently");
                } finally {
                    activeBranches.decrementAndGet();
                }
                if (stage.id().equals("testing") && testingAttempts.incrementAndGet() == 1)
                    throw new IllegalStateException("Transient test worker failure");
            }
            return "Completed " + stage.id();
        };
        service = new RunService(worker);

        EngineeringRun planned = service.create(new CreateRunRequest(
                "Build a workflow with measurable acceptance criteria.", ProjectScenario.GREENFIELD, "",
                List.of("Produces a review artifact")));
        service.execute(planned.id());
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Implementation approved"));

        assertTrue(peakBranches.get() >= 2);
        assertTrue(receivedUpstreamContext.get());
        assertEquals(1, service.orchestrationSnapshot(planned.id()).metrics().retries());
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("STAGE_RECOVERED")));
    }

    @Test void testingFallbackSafeStopsReleaseAndRollsBackLocalOutputs() {
        StageWorker worker = (stage, run, priorOutputs) -> {
            if (stage.id().equals("testing")) throw new IllegalStateException("Test runner unavailable");
            return "Completed " + stage.id();
        };
        service = new RunService(worker);
        EngineeringRun planned = service.create(new CreateRunRequest(
                "Build a workflow with measurable acceptance criteria.", ProjectScenario.GREENFIELD, "",
                List.of("Produces a review artifact")));
        service.execute(planned.id());
        service.review(planned.id(), new ReviewRequest(ReviewDecision.APPROVE, "Design approved"));
        EngineeringRun stopped = service.review(planned.id(),
                new ReviewRequest(ReviewDecision.APPROVE, "Implementation approved"));

        assertEquals(RunStatus.SAFE_STOPPED, stopped.status());
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("STAGE_FALLBACK")));
        assertTrue(service.auditTrail(planned.id()).stream().anyMatch(event -> event.eventType().equals("STAGE_ROLLBACK")));
        assertEquals(1, service.orchestrationSnapshot(planned.id()).metrics().retries());
        assertEquals(1, service.orchestrationSnapshot(planned.id()).metrics().fallbacks());
        assertTrue(service.orchestrationSnapshot(planned.id()).metrics().rollbacks() > 0);
    }
}
