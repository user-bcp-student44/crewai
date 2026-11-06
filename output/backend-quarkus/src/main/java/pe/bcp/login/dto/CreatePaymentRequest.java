// CreatePaymentRequest.java
package pe.bcp.login.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import pe.bcp.login.service.PaymentCommand;

/**
 * Entrada de POST /api/v1/payments. Los campos son un SUPUESTO pendiente de confirmar con Arquitectura.
 * Importe en céntimos de PEN.
 */
public record CreatePaymentRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 64, message = "debe tener máximo 64 caracteres")
        @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "contiene caracteres no permitidos")
        String payerWalletId,

        @NotBlank(message = "es obligatorio")
        @Size(max = 64, message = "debe tener máximo 64 caracteres")
        @Pattern(regexp = "^[A-Za-z0-9_.@-]*$", message = "contiene caracteres no permitidos")
        String destination,

        @NotNull(message = "es obligatorio")
        @Positive(message = "debe ser mayor que 0")
        Long amountCents,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^PEN$", message = "solo se admite PEN")
        String currency,

        @Size(max = 140, message = "debe tener máximo 140 caracteres")
        String description) {

    public PaymentCommand toCommand() {
        String cleanDescription = (description == null || description.isBlank()) ? null : description.trim();
        return new PaymentCommand(payerWalletId, destination, amountCents, currency, cleanDescription);
    }
}