// BackendUnavailableException.java
package pe.bcp.login.service;

/**
 * El backend (mock) no respondió a tiempo o devolvió un error de infraestructura.
 * El resultado del pago en el backend es desconocido.
 */
public class BackendUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}