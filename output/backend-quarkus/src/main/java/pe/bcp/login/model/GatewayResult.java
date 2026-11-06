// GatewayResult.java
package pe.bcp.login.port;

/** Respuesta cruda del proveedor/mock. El estado terminal lo decide PaymentStatusMapper. */
public record GatewayResult(String rawStatus, String transactionReference) {
}