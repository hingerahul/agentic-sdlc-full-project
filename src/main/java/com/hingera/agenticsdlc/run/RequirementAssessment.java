package com.hingera.agenticsdlc.run;

import java.util.List;

public record RequirementAssessment(
        ProjectScenario scenario,
        RequirementClarity clarity,
        List<String> ambiguityReasons,
        List<String> clarificationQuestions,
        List<String> assumptions,
        List<String> acceptanceCriteria,
        String suppliedCodebaseContext
) {
    public RequirementAssessment {
        ambiguityReasons = ambiguityReasons == null ? List.of() : List.copyOf(ambiguityReasons);
        clarificationQuestions = clarificationQuestions == null ? List.of() : List.copyOf(clarificationQuestions);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        suppliedCodebaseContext = suppliedCodebaseContext == null ? "" : suppliedCodebaseContext;
    }
}
