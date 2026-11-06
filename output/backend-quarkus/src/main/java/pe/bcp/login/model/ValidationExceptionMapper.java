// ValidationExceptionMapper.java
package pe.bcp.login.resource;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import pe.bcp.login.dto.ErrorResponse;
import pe.bcp.login.dto.ErrorResponse.FieldError;
import pe.bcp.login.exception.OnboardingValidationException;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<OnboardingValidationException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(OnboardingValidationException exception) {
        List<FieldError> fields = exception.getFieldErrors().entrySet().stream()
                .map(e -> new FieldError(e.getKey(), e.getValue()))
                .toList();
        String correlationId = CorrelationIds.resolve(headers.getHeaderString(CorrelationIds.HEADER));
        ErrorResponse body = new ErrorResponse("VALIDATION_ERROR", exception.getMessage(), correlationId, fields);
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIds.HEADER, correlationId)
                .entity(body)
                .build();
    }
}