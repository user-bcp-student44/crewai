// PaymentCommand.java
package pe.bcp.login.service;

/**
 * Datos validados de una solicitud de pago. Importe en céntimos de PEN.
 */
public record PaymentCommand(
        String payerWalletId,
        String destination,
        long amountCents,
        String currency,
        String description) {
}