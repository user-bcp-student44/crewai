// PaymentResponse.java
package pe.bcp.login.dto;

import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String status,
        boolean terminal,
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency,
        String transactionReference,
        String failureReason,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt) {
}