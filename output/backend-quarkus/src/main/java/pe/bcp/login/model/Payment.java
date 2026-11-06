// Payment.java
package pe.bcp.login.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Payment(
        UUID id,
        String payerId,
        String providerId,
        String channel,
        Destination destination,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String transactionReference,
        String failureReason,
        String idempotencyKey,
        String requestHash,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt) {

    public static Payment newPending(
            UUID id,
            String payerId,
            String providerId,
            String channel,
            Destination destination,
            long amountMinor,
            String currency,
            String idempotencyKey,
            String requestHash,
            Instant now,
            Duration ttl) {
        return new Payment(id, payerId, providerId, channel, destination, amountMinor, currency,
                PaymentStatus.PENDING, null, null, idempotencyKey, requestHash, now, now.plus(ttl), null);
    }
}