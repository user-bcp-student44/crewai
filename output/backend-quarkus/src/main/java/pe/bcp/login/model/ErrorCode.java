// ErrorCode.java
package pe.bcp.login.service;

/**
 * Códigos de error de dominio con su estado HTTP asociado.
 */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    IDEMPOTENCY_KEY_REQUIRED(400),
    AMOUNT_LIMIT_EXCEEDED(400),
    PAYMENT_NOT_FOUND(404),
    IDEMPOTENCY_KEY_REUSED(409),
    IDEMPOTENCY_IN_PROGRESS(409),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}