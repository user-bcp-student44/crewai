// BackendPaymentResult.java
package pe.bcp.login.service;

import pe.bcp.login.model.PaymentStatus;

/**
 * Resultado del backend ya traducido a estados de dominio por el adaptador.
 * failureCode debe estar normalizado a un código de dominio.
 */
public record BackendPaymentResult(
        String backendPaymentId,
        PaymentStatus status,
        String failureCode) {
}