package com.hingera.agenticsdlc.run;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StageOutputRevision(
        @NotBlank @Size(max = 20000) String output,
        @Size(max = 2000) String rationale
) {
}
