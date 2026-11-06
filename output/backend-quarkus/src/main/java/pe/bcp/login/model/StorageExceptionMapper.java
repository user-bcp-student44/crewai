// StorageExceptionMapper.java
package pe.bcp.login.resource;

import java.util.List;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import pe.bcp.login.dto.ErrorResponse;
import pe.bcp.login.exception.StorageException;

/** Respuesta genérica: nunca expone SQL, trazas ni detalles de la base de datos. */
@Provider
public class StorageExceptionMapper implements ExceptionMapper<StorageException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(StorageException exception) {
        String correlationId = CorrelationIds.resolve(headers.getHeaderString(CorrelationIds.HEADER));
        ErrorResponse body = new ErrorResponse(
                "INTERNAL_ERROR",
                "No pudimos procesar tu solicitud. Inténtalo nuevamente en unos minutos.",
                correlationId,
                List.of());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIds.HEADER, correlationId)
                .entity(body)
                .build();
    }
}