package com.hingera.agenticsdlc.run;

import java.time.Instant;
import java.util.UUID;

public record RunAuditEvent(UUID eventId, UUID runId, Instant occurredAt, String eventType,
                            String stageId, String actor, String detail) {
}
