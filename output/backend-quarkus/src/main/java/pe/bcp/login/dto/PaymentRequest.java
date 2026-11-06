// PaymentRequest.java
package pe.bcp.login.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Entrada comun de preview y create. Monto en unidades menores (long) segun Money del contrato. */
public record PaymentRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String providerId,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String channel,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String destinationType,

        @NotBlank(message = "es obligatorio")
        @Size(max = 64, message = "longitud máxima 64")
        String destinationIdentifier,

        @Positive(message = "debe ser mayor que cero")
        long amountMinor,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Z]{3}$", message = "debe ser un código de 3 letras mayúsculas")
        String currency) {
}