package com.hingera.agenticsdlc.run;

import java.util.List;

public record ValidationResult(
        boolean passed,
        List<String> checks,
        List<String> issues
) {
    public ValidationResult {
        checks = List.copyOf(checks);
        issues = List.copyOf(issues);
    }
}
