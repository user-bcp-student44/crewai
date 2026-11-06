// PaymentRecord.java
package pe.bcp.login.model;

import java.time.Instant;

/**
 * Pago persistido por el BFF. Los importes son enteros en céntimos de PEN.
 */
public record PaymentRecord(
        String paymentId,
        String backendPaymentId,
        String payerWalletId,
        String destination,
        long amountCents,
        String currency,
        String description,
        PaymentStatus status,
        String failureCode,
        Instant createdAt,
        Instant updatedAt) {

    public PaymentRecord withOutcome(PaymentStatus newStatus,
                                     String newBackendPaymentId,
                                     String newFailureCode,
                                     Instant newUpdatedAt) {
        return new PaymentRecord(
                paymentId,
                newBackendPaymentId,
                payerWalletId,
                destination,
                amountCents,
                currency,
                description,
                newStatus,
                newFailureCode,
                createdAt,
                newUpdatedAt);
    }
}