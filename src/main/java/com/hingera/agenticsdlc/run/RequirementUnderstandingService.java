package com.hingera.agenticsdlc.run;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic intake analysis. This does not claim semantic or repository-wide understanding. */
@Service
public class RequirementUnderstandingService {
    private static final Pattern BROWNFIELD_CUE = Pattern.compile("\\b(existing|legacy|refactor|bug|fix|enhance|update|migrate|backward compatibility|current service)\\b");
    private static final List<String> VAGUE_TERMS = List.of("better", "improve", "user-friendly", "robust", "fast", "scalable", "modernize", "easy to use");

    public RequirementAssessment assess(String requirement, ProjectScenario requestedScenario,
                                       String codebaseContext, List<String> acceptanceCriteria) {
        String text = requirement == null ? "" : requirement.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        ProjectScenario scenario = requestedScenario == null || requestedScenario == ProjectScenario.AUTO
                ? (BROWNFIELD_CUE.matcher(lower).find() ? ProjectScenario.BROWNFIELD : ProjectScenario.GREENFIELD)
                : requestedScenario;
        List<String> criteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
        List<String> reasons = new ArrayList<>();
        for (String term : VAGUE_TERMS) {
            if (lower.contains(term)) {
                reasons.add("Vague outcome term requires a measurable definition: '" + term + "'.");
            }
        }
        if (criteria.isEmpty()) {
            reasons.add("No explicit acceptance criteria were supplied.");
        }
        if (scenario == ProjectScenario.BROWNFIELD && (codebaseContext == null || codebaseContext.isBlank())) {
            reasons.add("Brownfield work was selected without codebase, module, API, or data-flow context.");
        }
        List<String> questions = new ArrayList<>();
        if (reasons.stream().anyMatch(reason -> reason.contains("Vague outcome"))) {
            questions.add("What observable behavior or measurable outcome defines success?");
        }
        if (criteria.isEmpty()) {
            questions.add("What acceptance criteria and verification evidence are required?");
        }
        if (scenario == ProjectScenario.BROWNFIELD && (codebaseContext == null || codebaseContext.isBlank())) {
            questions.add("Which repository, services, modules, APIs, and data flows are in scope?");
        }
        List<String> assumptions = reasons.isEmpty() ? List.of()
                : List.of("No unstated implementation assumptions are treated as approved; resolve the listed questions before relying on this plan.");
        RequirementClarity clarity = reasons.isEmpty() ? RequirementClarity.CLEAR : RequirementClarity.NEEDS_CLARIFICATION;
        return new RequirementAssessment(scenario, clarity, reasons, questions, assumptions, criteria, codebaseContext);
    }
}
