package com.hingera.agenticsdlc.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunOrchestrationServiceTest {

    private final RunOrchestrationService service = new RunOrchestrationService();

    @Test
    void buildsDependencyAwareStageGraph() {
        var stages = service.defaultGraph();

        assertEquals(6, stages.size());
        assertTrue(stages.stream().anyMatch(stage -> stage.id().equals("requirement-analysis")));
        assertTrue(stages.stream().anyMatch(stage -> stage.id().equals("validation")));
        assertTrue(stages.stream()
                .filter(stage -> stage.id().equals("validation"))
                .findFirst()
                .orElseThrow()
                .dependencies()
                .contains("implementation-plan"));
    }

    @Test
    void validatesGraphAndRequiresApprovalGuardrails() {
        assertTrue(service.validationIssues().isEmpty());
        assertTrue(service.buildPlanSummary().contains("Human approval required"));
    }

    @Test
    void producesRuntimeMetrics() {
        var metrics = service.metricsFromCompletedStages(4);

        assertEquals(6, metrics.totalStages());
        assertEquals(4, metrics.completedStages());
        assertTrue(metrics.successRate() > 0.0);
    }
}
