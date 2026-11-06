// DomainException.java
package pe.bcp.login.service;

/**
 * Error de negocio traducible a una respuesta HTTP coherente.
 * El mensaje debe ser apto para el usuario y no contener datos sensibles.
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final boolean retryable;

    public DomainException(ErrorCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public ErrorCode getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}