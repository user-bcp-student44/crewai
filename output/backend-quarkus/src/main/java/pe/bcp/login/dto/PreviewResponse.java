// PreviewResponse.java
package pe.bcp.login.dto;

public record PreviewResponse(
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency) {
}