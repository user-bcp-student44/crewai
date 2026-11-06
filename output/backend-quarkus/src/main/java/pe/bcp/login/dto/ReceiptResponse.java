// ReceiptResponse.java
package pe.bcp.login.dto;

import java.time.Instant;

public record ReceiptResponse(
        String paymentId,
        String transactionReference,
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency,
        Instant completedAt) {
}