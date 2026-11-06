// BackendPaymentCommand.java
package pe.bcp.login.service;

/**
 * Solicitud de pago hacia el backend. paymentId es la referencia del BFF.
 */
public record BackendPaymentCommand(
        String paymentId,
        String payerWalletId,
        String destination,
        long amountCents,
        String currency,
        String description) {
}