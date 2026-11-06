// ConstraintViolationExceptionMapper.java
package pe.bcp.login.resource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Comparator;
import java.util.List;
import pe.bcp.login.dto.ErrorResponse;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException e) {
        List<ErrorResponse.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v), v.getMessage()))
                .sorted(Comparator.comparing(ErrorResponse.FieldError::field)
                        .thenComparing(ErrorResponse.FieldError::message))
                .toList();
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("VALIDATION_ERROR", "La solicitud contiene datos inválidos.", fields))
                .build();
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String last = "request";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null && !node.getName().isBlank()) {
                last = node.getName();
            }
        }
        return last;
    }
}