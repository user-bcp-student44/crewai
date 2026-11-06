// PaymentGatewayPort.java
package pe.bcp.login.port;

import pe.bcp.login.domain.Payment;

/**
 * Puerto hacia el proveedor/mock. Debe usar payment.id() como referencia idempotente hacia el proveedor.
 * Debe lanzar GatewayUnavailableException ante timeout o error de red (resultado desconocido).
 * ADAPTADOR PENDIENTE: el contrato del mock no fue recibido.
 */
public interface PaymentGatewayPort {

    GatewayResult initiate(Payment payment);

    GatewayResult fetchStatus(Payment payment);
}