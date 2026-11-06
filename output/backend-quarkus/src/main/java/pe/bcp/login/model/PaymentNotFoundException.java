// PaymentNotFoundException.java
package pe.bcp.login.domain;

/** Recurso inexistente (o ajeno al pagador) -> HTTP 404. */
public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(String code, String message) {
        super(code, message);
    }
}