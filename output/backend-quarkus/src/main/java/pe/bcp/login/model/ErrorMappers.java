// ErrorMappers.java
package pe.bcp.login.resource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import pe.bcp.login.dto.ErrorResponse;
import pe.bcp.login.service.DomainException;
import pe.bcp.login.service.ErrorCode;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Traduce excepciones al formato de error del BFF sin exponer trazas, SQL ni datos sensibles.
 * El detalle técnico solo se registra en el log junto con el traceId.
 */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapDomain(DomainException e) {
        String traceId = newTraceId();
        LOG.infof("traceId=%s code=%s", traceId, e.getCode());
        ErrorResponse body = new ErrorResponse(e.getCode().name(), e.getMessage(), e.isRetryable(), traceId);
        return RestResponse.status(e.getCode().httpStatus(), body);
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapValidation(ConstraintViolationException e) {
        String traceId = newTraceId();
        String detail = e.getConstraintViolations().stream()
                .map(ErrorMappers::describe)
                .sorted()
                .collect(Collectors.joining("; "));
        LOG.infof("traceId=%s validación fallida", traceId);
        ErrorResponse body = new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(),
                detail.isEmpty() ? "La solicitud no es válida." : detail,
                false,
                traceId);
        return RestResponse.status(400, body);
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapWebApplication(WebApplicationException e) {
        String traceId = newTraceId();
        int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
        if (status >= 500) {
            LOG.errorf(e, "traceId=%s error HTTP interno", traceId);
            return RestResponse.status(500, internal(traceId));
        }
        LOG.infof("traceId=%s solicitud rechazada status=%d", traceId, status);
        String code;
        String message;
        if (status == 404) {
            code = "RESOURCE_NOT_FOUND";
            message = "El recurso solicitado no existe.";
        } else if (status == 400) {
            code = ErrorCode.VALIDATION_ERROR.name();
            message = "La solicitud está mal formada.";
        } else {
            code = "REQUEST_NOT_ACCEPTED";
            message = "La solicitud no pudo ser aceptada.";
        }
        return RestResponse.status(status, new ErrorResponse(code, message, false, traceId));
    }

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> mapUnexpected(Throwable e) {
        String traceId = newTraceId();
        LOG.errorf(e, "traceId=%s error inesperado", traceId);
        return RestResponse.status(500, internal(traceId));
    }

    private static ErrorResponse internal(String traceId) {
        return new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(),
                "Ocurrió un error interno. Intenta nuevamente más tarde.",
                true,
                traceId);
    }

    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int index = path.lastIndexOf('.');
        String field = index >= 0 ? path.substring(index + 1) : path;
        return field + ": " + violation.getMessage();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}