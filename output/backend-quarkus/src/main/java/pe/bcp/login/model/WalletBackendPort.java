// WalletBackendPort.java
package pe.bcp.login.service;

/**
 * Puerto de salida hacia el BCP Wallet Mock (grupo 1).
 * La implementación pertenece al adaptador de la historia #9.
 */
public interface WalletBackendPort {

    /**
     * Crea un pago en el backend.
     *
     * @throws BackendUnavailableException si el backend no responde o falla por infraestructura.
     */
    BackendPaymentResult createPayment(BackendPaymentCommand command);
}