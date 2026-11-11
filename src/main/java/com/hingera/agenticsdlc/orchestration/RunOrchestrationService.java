package com.hingera.agenticsdlc.orchestration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RunOrchestrationService {

    private static final List<StageDefinition> DEFAULT_GRAPH = List.of(
            new StageDefinition(
                    "requirement-analysis",
                    "Requirement analysis",
                    "Normalize the input requirement and preserve it as the source of truth.",
                    List.of(),
                    false,
                    RiskLevel.LOW
            ),
            new StageDefinition(
                    "decomposition",
                    "Decomposition",
                    "Break the requirement into engineering tasks and review gates.",
                    List.of("requirement-analysis"),
                    false,
                    RiskLevel.MEDIUM
            ),
            new StageDefinition(
                    "design-review",
                    "Design review",
                    "Assess the proposed architecture, risk, and assumptions before implementation planning.",
                    List.of("decomposition"),
                    true,
                    RiskLevel.MEDIUM
            ),
            new StageDefinition(
                    "implementation-plan",
                    "Implementation plan",
                    "Translate validated design into actionable implementation steps and verification criteria.",
                    List.of("design-review"),
                    false,
                    RiskLevel.HIGH
            ),
            new StageDefinition(
                    "validation",
                    "Validation",
                    "Check generated outputs against guardrails, acceptance criteria, and safety rules.",
                    List.of("implementation-plan"),
                    true,
                    RiskLevel.HIGH
            ),
            new StageDefinition(
                    "review-package",
                    "Review package",
                    "Assemble the final engineering summary and explicit approval checkpoint for human review.",
                    List.of("validation"),
                    true,
                    RiskLevel.MEDIUM
            )
    );

    public List<StageDefinition> defaultGraph() {
        return DEFAULT_GRAPH;
    }

    public List<DependencyEdge> dependencyEdges() {
        List<DependencyEdge> edges = new ArrayList<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            for (String dependency : stage.dependencies()) {
                edges.add(new DependencyEdge(dependency, stage.id(), ExecutionGate.EXIT));
            }
        }
        return edges;
    }

    public List<String> validationIssues() {
        List<String> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (StageDefinition stage : DEFAULT_GRAPH) {
            if (!ids.add(stage.id())) {
                issues.add("Duplicate stage id detected: " + stage.id());
            }
        }

        Map<String, StageDefinition> index = new HashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            index.put(stage.id(), stage);
        }

        for (StageDefinition stage : DEFAULT_GRAPH) {
            for (String dependency : stage.dependencies()) {
                if (!index.containsKey(dependency)) {
                    issues.add("Stage '" + stage.id() + "' depends on missing stage '" + dependency + "'.");
                }
            }
        }

        if (hasCycle()) {
            issues.add("Orchestration graph contains a cycle and cannot execute safely.");
        }

        return issues;
    }

    public boolean hasCycle() {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            adjacency.put(stage.id(), stage.dependencies());
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String stageId : adjacency.keySet()) {
            if (isCyclic(stageId, adjacency, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCyclic(String stageId, Map<String, List<String>> adjacency,
                             Set<String> visiting, Set<String> visited) {
        if (visiting.contains(stageId)) {
            return true;
        }
        if (visited.contains(stageId)) {
            return false;
        }

        visiting.add(stageId);
        for (String dependency : adjacency.getOrDefault(stageId, List.of())) {
            if (isCyclic(dependency, adjacency, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(stageId);
        visited.add(stageId);
        return false;
    }

    public String buildPlanSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("## Governance-aware orchestration graph");
        lines.add("This run uses an explicit dependency-aware SDLC workflow with gated progression and human review checkpoints.");

        for (StageDefinition stage : DEFAULT_GRAPH) {
            String dependencyDescription = stage.dependencies().isEmpty()
                    ? "No upstream dependency"
                    : "Depends on: " + String.join(", ", stage.dependencies());
            String approvalDescription = stage.requiresHumanApproval() ? "Human approval required" : "Automated stage";
            lines.add("- " + stage.name() + " (" + stage.id() + ") — " + stage.description()
                    + "; " + dependencyDescription + "; " + approvalDescription + "; risk=" + stage.riskLevel() + ".");
        }

        lines.add("Dependency edges: " + dependencyEdges().stream()
                .map(edge -> edge.fromStage() + " -> " + edge.toStage())
                .sorted()
                .toList());
        lines.add("Control policy: execution advances only when upstream dependencies have completed and required approval gates have been satisfied.");
        return String.join("\n", lines);
    }

    public RuntimeMetrics metricsFor(int completedStages, int totalStages, int retryCount,
                                    int rollbackCount, int approvalCheckpoints,
                                    int criticalFailures, long latencyMs) {
        double successRate = totalStages == 0 ? 0.0 : (completedStages * 100.0) / totalStages;
        return new RuntimeMetrics(totalStages, completedStages, retryCount, rollbackCount,
                approvalCheckpoints, criticalFailures, successRate, latencyMs);
    }

    public RuntimeMetrics metricsFromCompletedStages(int completedStages) {
        int totalStages = DEFAULT_GRAPH.size();
        long approvalCheckpoints = DEFAULT_GRAPH.stream().filter(StageDefinition::requiresHumanApproval).count();
        return metricsFor(completedStages, totalStages, 0, 0, (int) approvalCheckpoints, 0, 0L);
    }
}
