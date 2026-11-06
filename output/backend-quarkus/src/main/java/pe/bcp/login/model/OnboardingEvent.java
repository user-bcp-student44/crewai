// OnboardingEvent.java
package pe.bcp.login.service;

import java.time.Instant;
import java.util.UUID;

public record OnboardingEvent(
        UUID eventId,
        String eventType,
        UUID sessionId,
        UUID userId,
        String stepId,
        String acquisitionChannel,
        Instant occurredAt,
        String correlationId) {

    public static final String ONBOARDING_STARTED = "onboarding_started";
}