// PersistenceFailureException.java
package pe.bcp.login.domain;

/** Fallo de persistencia. No transporta SQL ni datos del fallo. */
public class PersistenceFailureException extends PaymentException {

    public PersistenceFailureException() {
        super("INTERNAL_ERROR", "Ocurrió un error inesperado. Intenta nuevamente más tarde.");
    }
}