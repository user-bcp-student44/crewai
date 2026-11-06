// PaymentConflictException.java
package pe.bcp.login.domain;

/** Duplicado o conflicto de estado -> HTTP 409. */
public class PaymentConflictException extends PaymentException {

    public PaymentConflictException(String code, String message) {
        super(code, message);
    }
}