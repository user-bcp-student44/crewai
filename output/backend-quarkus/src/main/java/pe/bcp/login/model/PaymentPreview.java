// PaymentPreview.java
package pe.bcp.login.domain;

public record PaymentPreview(
        String providerId,
        String channel,
        Destination destination,
        long amountMinor,
        String currency) {
}