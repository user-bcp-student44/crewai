// GatewayUnavailableException.java
package pe.bcp.login.domain;

/** El proveedor/mock no respondio de forma definitiva. El resultado del pago es desconocido. */
public class GatewayUnavailableException extends PaymentException {

    public GatewayUnavailableException() {
        super("PAYMENT_PROVIDER_UNAVAILABLE", "El proveedor de pagos no está disponible en este momento.");
    }
}