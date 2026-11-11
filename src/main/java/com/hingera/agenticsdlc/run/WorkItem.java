package com.hingera.agenticsdlc.run;

public record WorkItem(
        int order,
        WorkItemKind kind,
        String title,
        String description,
        WorkItemStatus status,
        String output
) {
}
