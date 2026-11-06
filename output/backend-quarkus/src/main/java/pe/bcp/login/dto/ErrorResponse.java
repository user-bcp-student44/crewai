// ErrorResponse.java
package pe.bcp.login.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** SUPUESTO: formato de error {code, message, fieldErrors?}; el formato del contrato no fue visible. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public ErrorResponse(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}