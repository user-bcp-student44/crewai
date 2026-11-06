// PaymentLimits.java
package pe.bcp.login.domain;

public record PaymentLimits(String providerId, String channel, String currency, long minMinor, long maxMinor) {
}