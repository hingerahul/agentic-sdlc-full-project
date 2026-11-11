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

    public List<String> validationIssues() {
        List<String> issues = new ArrayList<>();
        Map<String, StageDefinition> index = new HashMap<>();
        for (StageDefinition stage : DEFAULT_GRAPH) {
            if (index.put(stage.id(), stage) != null) issues.add("Duplicate stage id detected: " + stage.id());
        }
        for (StageDefinition stage : DEFAULT_GRAPH) for (String dependency : stage.dependencies()) {
            if (!index.containsKey(dependency)) issues.add("Stage '" + stage.id() + "' depends on missing stage '" + dependency + "'.");
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
        lines.add("Testing and documentation are independent branches after implementation and synchronize at release readiness.");
        for (StageDefinition stage : DEFAULT_GRAPH) {
            lines.add("- " + stage.id() + " (" + stage.riskLevel() + ") dependencies=" + stage.dependencies()
                    + (stage.requiresHumanApproval() ? "; human approval required" : ""));
        }
        lines.add("Graph is executed in dependency order by this bounded runtime; work is not concurrently dispatched.");
        return String.join("\n", lines);
    }

    public RuntimeMetrics metricsFor(int completed, int total, int retries, int rollbacks,
                                     int approvals, int failures, long latencyMs) {
        double rate = total == 0 ? 0.0 : completed * 100.0 / total;
        return new RuntimeMetrics(total, completed, retries, rollbacks, approvals, failures, rate, latencyMs);
    }
}
