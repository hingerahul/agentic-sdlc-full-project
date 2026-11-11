package com.hingera.agenticsdlc.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOrchestrationServiceTest {

    private final RunOrchestrationService service = new RunOrchestrationService();

    @Test
    void buildsDependencyAwareStageGraph() {
        var stages = service.defaultGraph();

        assertEquals(7, stages.size());
        assertTrue(stages.stream().anyMatch(stage -> stage.id().equals("requirement-analysis")));
        assertTrue(stages.stream().anyMatch(stage -> stage.id().equals("release-readiness")));
        assertTrue(stages.stream()
                .filter(stage -> stage.id().equals("testing"))
                .findFirst()
                .orElseThrow()
                .dependencies().contains("implementation"));
        assertTrue(service.executionWaves().stream().anyMatch(wave -> wave.containsAll(List.of("testing", "documentation"))));
        assertTrue(service.executionGates().stream().anyMatch(gate -> gate.gateType().equals(ExecutionGate.ENTRY)));
    }

    @Test
    void validatesGraphAndRequiresApprovalGuardrails() {
        assertTrue(service.validationIssues().isEmpty());
        assertTrue(service.buildPlanSummary().contains("human approval required"));
    }

    @Test
    void producesRuntimeMetrics() {
        var metrics = service.metricsFor(4, 7, 0, 0, 0, 0, 25);

        assertEquals(7, metrics.totalStages());
        assertEquals(4, metrics.completedStages());
        assertTrue(metrics.successRate() > 0.0);
    }

    @Test
    void governancePolicyRequiresApprovalAndDeniesExternalActions() {
        GovernancePolicyEngine policy = new GovernancePolicyEngine();
        StageDefinition architecture = service.defaultGraph().stream()
                .filter(stage -> stage.id().equals("architecture-design")).findFirst().orElseThrow();

        assertEquals("HOLD", policy.evaluateStage(architecture, false).outcome());
        assertEquals("ALLOW", policy.evaluateStage(architecture, true).outcome());
        assertEquals("ALLOW", policy.evaluateCapability("LOCAL_PLANNING").outcome());
        assertEquals("DENY", policy.evaluateCapability("DEPLOY").outcome());
    }
}
