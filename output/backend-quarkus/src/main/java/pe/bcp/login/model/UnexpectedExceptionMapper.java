// UnexpectedExceptionMapper.java
package pe.bcp.login.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import pe.bcp.login.dto.ErrorResponse;

/** Ultima red de seguridad: nunca devuelve trazas, SQL ni mensajes internos. */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        if (t instanceof WebApplicationException web) {
            return web.getResponse();
        }
        if (t instanceof JsonProcessingException) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("INVALID_JSON", "El cuerpo de la solicitud no es un JSON válido."))
                    .build();
        }
        LOG.error("Error no controlado", t);
        return Response.status(500)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("INTERNAL_ERROR",
                        "Ocurrió un error inesperado. Intenta nuevamente más tarde."))
                .build();
    }
}