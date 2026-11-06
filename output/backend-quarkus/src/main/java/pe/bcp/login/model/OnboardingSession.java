// OnboardingSession.java
package pe.bcp.login.model;

import java.time.Instant;
import java.util.UUID;

public record OnboardingSession(
        UUID id,
        UUID userId,
        OnboardingStatus status,
        int currentStepIndex,
        int version,
        WalletStatus walletStatus,
        String acquisitionChannel,
        Instant startedAt,
        Instant updatedAt) {
}