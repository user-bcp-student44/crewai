// CreatePaymentOutcome.java
package pe.bcp.login.service;

import pe.bcp.login.model.PaymentRecord;

/**
 * Resultado de crear un pago. replayed es true si se devolvió un pago ya registrado
 * bajo la misma clave de idempotencia.
 */
public record CreatePaymentOutcome(PaymentRecord payment, boolean replayed) {
}