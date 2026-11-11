package com.hingera.agenticsdlc.run;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @NotBlank @Size(min = 20, max = 10000) String requirement
) {
}
