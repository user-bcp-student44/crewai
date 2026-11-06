// PaymentExceptionMapper.java
package pe.bcp.login.resource;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.PaymentConflictException;
import pe.bcp.login.domain.PaymentException;
import pe.bcp.login.domain.PaymentNotFoundException;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.ErrorResponse;

@Provider
public class PaymentExceptionMapper implements ExceptionMapper<PaymentException> {

    private static final Logger LOG = Logger.getLogger(PaymentExceptionMapper.class);

    @Override
    public Response toResponse(PaymentException e) {
        int status;
        if (e instanceof PaymentValidationException) {
            status = 400;
        } else if (e instanceof PaymentNotFoundException) {
            status = 404;
        } else if (e instanceof PaymentConflictException) {
            status = 409;
        } else if (e instanceof GatewayUnavailableException) {
            status = 503; // SUPUESTO: 503 no figura en la lista del encargo; confirmar con Arquitectura.
        } else {
            status = 500;
        }
        if (status >= 500) {
            LOG.errorf("Error de servidor controlado: %s", e.code());
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(e.code(), e.getMessage()))
                .build();
    }
}