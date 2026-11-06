// RepositoryException.java
package pe.bcp.login.repository;

/**
 * Error de persistencia. No transporta SQL ni datos de la solicitud.
 */
public class RepositoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}