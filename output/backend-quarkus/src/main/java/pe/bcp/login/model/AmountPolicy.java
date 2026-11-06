// AmountPolicy.java
package pe.bcp.login.domain;

public final class AmountPolicy {

    private AmountPolicy() {
    }

    public static void validate(long amountMinor, String currency, PaymentLimits limits) {
        if (amountMinor <= 0) {
            throw new PaymentValidationException("AMOUNT_INVALID", "El monto debe ser mayor que cero.");
        }
        if (limits.currency() == null || !limits.currency().equals(currency)) {
            throw new PaymentValidationException("CURRENCY_NOT_SUPPORTED",
                    "La moneda no está soportada para este proveedor y canal.");
        }
        if (amountMinor < limits.minMinor() || amountMinor > limits.maxMinor()) {
            throw new PaymentValidationException("AMOUNT_OUT_OF_RANGE",
                    "El monto está fuera del rango permitido para este proveedor y canal.");
        }
    }
}