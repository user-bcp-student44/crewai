// PaymentException.java
package pe.bcp.login.domain;

public abstract class PaymentException extends RuntimeException {

    private final String code;

    protected PaymentException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}