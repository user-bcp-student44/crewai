// IdempotencyRecord.java
package pe.bcp.login.model;

import java.time.Instant;

/**
 * Registro de idempotencia asociado a una clave enviada por el cliente.
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        String paymentId,
        Instant createdAt) {
}