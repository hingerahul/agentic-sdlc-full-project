package com.hingera.agenticsdlc.run;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotNull ReviewDecision decision,
        @Size(max = 2000) String feedback
) {
}
