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
            new StageDefinition("requirement-analysis", "Requirement analysis", "Normalize intent, constraints, and acceptance evidence.", List.of(), false, RiskLevel.LOW),
            new StageDefinition("codebase-analysis", "Codebase analysis", "Review supplied brownfield context and identify impacted areas; no repository scan is performed.", List.of("requirement-analysis"), false, RiskLevel.MEDIUM),
            new StageDefinition("architecture-design", "Architecture and design", "Prepare design rationale and identify risks before implementation.", List.of("requirement-analysis", "codebase-analysis"), true, RiskLevel.HIGH),
            new StageDefinition("implementation", "Implementation", "Produce a bounded implementation plan; this prototype does not modify source code.", List.of("architecture-design"), true, RiskLevel.HIGH),
            new StageDefinition("testing", "Testing", "Define and validate test strategy; no target project tests are run.", List.of("implementation"), false, RiskLevel.MEDIUM),
            new StageDefinition("documentation", "Documentation", "Prepare documentation plan and record scope limitations.", List.of("implementation"), false, RiskLevel.LOW),
            new StageDefinition("release-readiness", "Release readiness", "Review validation evidence, unresolved risks, and human approval.", List.of("testing", "documentation"), true, RiskLevel.HIGH)
    );

    public List<StageDefinition> defaultGraph() { return DEFAULT_GRAPH; }

    public List<DependencyEdge> dependencyEdges() {
        return DEFAULT_GRAPH.stream().flatMap(stage -> stage.dependencies().stream()
                .map(dependency -> new DependencyEdge(dependency, stage.id(), ExecutionGate.EXIT))).toList();
    }

    public List<ExecutionGate> executionGates() {
        List<ExecutionGate> gates = new ArrayList<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            String entry = stage.dependencies().isEmpty()
                    ? "Requirement intake is present and structurally valid."
                    : "All dependencies must pass their exit gates: " + String.join(", ", stage.dependencies()) + ".";
            gates.add(new ExecutionGate(stage.id(), ExecutionGate.ENTRY, entry));
            gates.add(new ExecutionGate(stage.id(), ExecutionGate.EXIT,
                    "Stage output is recorded and its declared checks pass."));
        }
        return List.copyOf(gates);
    }

    public List<List<String>> executionWaves() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            int level = stage.dependencies().stream().mapToInt(dependency -> levels.get(dependency) + 1).max().orElse(0);
            levels.put(stage.id(), level);
        }
        int maxLevel = levels.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<String>> waves = new ArrayList<>();
        for (int level = 0; level <= maxLevel; level++) {
            int currentLevel = level;
            waves.add(levels.entrySet().stream().filter(entry -> entry.getValue() == currentLevel)
                    .map(Map.Entry::getKey).toList());
        }
        return List.copyOf(waves);
    }

    public List<String> validationIssues() {
        List<String> issues = new ArrayList<>();
        Map<String, StageDefinition> index = new HashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            if (index.put(stage.id(), stage) != null) issues.add("Duplicate stage id detected: " + stage.id());
        }
        for (StageDefinition stage : DEFAULT_GRAPH) for (String dependency : stage.dependencies()) {
            if (!index.containsKey(dependency)) issues.add("Stage '" + stage.id() + "' depends on missing stage '" + dependency + "'.");
        }
        for (StageDefinition stage : DEFAULT_GRAPH) {
            if (stage.riskLevel() == RiskLevel.HIGH && !stage.requiresHumanApproval())
                issues.add("High-risk stage '" + stage.id() + "' must require human approval.");
        }
        if (hasCycle()) issues.add("Orchestration graph contains a cycle and cannot execute safely.");
        return List.copyOf(issues);
    }

    public boolean hasCycle() {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) adjacency.put(stage.id(), stage.dependencies());
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String id : adjacency.keySet()) if (isCyclic(id, adjacency, visiting, visited)) return true;
        return false;
    }

    private boolean isCyclic(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) return true;
        if (visited.contains(id)) return false;
        visiting.add(id);
        for (String dependency : graph.getOrDefault(id, List.of())) if (isCyclic(dependency, graph, visiting, visited)) return true;
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    public String buildPlanSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("## SDLC dependency graph");
        lines.add("Each stage has entry and exit gates. Stages in a wave are parallel-ready; testing and documentation synchronize at release readiness.");
        for (StageDefinition stage : DEFAULT_GRAPH) {
            lines.add("- " + stage.id() + " (" + stage.riskLevel() + ") dependencies=" + stage.dependencies()
                    + (stage.requiresHumanApproval() ? "; human approval required" : ""));
        }
        lines.add("Execution waves: " + executionWaves());
        lines.add("Independent local planning stages execute concurrently; no external agents or jobs are dispatched.");
        return String.join("\n", lines);
    }

    public RuntimeMetrics metricsFor(int completed, int total, int retries, int rollbacks,
                                     int approvals, int failures, long endToEndLatencyMs) {
        double rate = total == 0 ? 0.0 : completed * 100.0 / total;
        return new RuntimeMetrics(total, completed, retries, rollbacks, approvals, failures, rate, endToEndLatencyMs, 0, 0);
    }
}
