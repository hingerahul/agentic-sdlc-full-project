package com.hingera.agenticsdlc.run;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRunRequest(
        @NotBlank @Size(min = 20, max = 10000) String requirement,
        ProjectScenario scenario,
        @Size(max = 20000) String codebaseContext,
        @Size(max = 50) List<@Size(max = 2000) String> acceptanceCriteria
) {
    public CreateRunRequest(String requirement) {
        this(requirement, ProjectScenario.AUTO, "", List.of());
    }
}
