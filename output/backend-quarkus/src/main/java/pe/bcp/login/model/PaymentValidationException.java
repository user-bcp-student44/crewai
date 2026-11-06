// PaymentValidationException.java
package pe.bcp.login.domain;

/** Error de validacion de entrada o de regla de negocio -> HTTP 400. */
public class PaymentValidationException extends PaymentException {

    public PaymentValidationException(String code, String message) {
        super(code, message);
    }
}