// OnboardingResource.java
package pe.bcp.login.resource;

import java.util.Map;
import java.util.UUID;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import pe.bcp.login.dto.OnboardingProgressResponse;
import pe.bcp.login.dto.StartOnboardingRequest;
import pe.bcp.login.exception.OnboardingValidationException;
import pe.bcp.login.service.OnboardingProgressAssembler;
import pe.bcp.login.service.OnboardingService;
import pe.bcp.login.service.StartResult;

@Path("/onboarding/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class OnboardingResource {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Inject
    SecurityIdentity identity;

    @Inject
    OnboardingService service;

    @Inject
    OnboardingProgressAssembler assembler;

    /**
     * E1. Iniciar o reanudar onboarding.
     * 201 si se crea; 200 si se reanuda (SUPUESTO: el contrato truncado no confirma el código de reanudación).
     */
    @POST
    public Response start(
            @HeaderParam(IDEMPOTENCY_HEADER) String idempotencyKey,
            @HeaderParam(CorrelationIds.HEADER) String correlationHeader,
            @Valid StartOnboardingRequest request) {

        String correlationId = CorrelationIds.resolve(correlationHeader);
        UUID userId = currentUserId();
        requireValidIdempotencyKey(idempotencyKey);

        String channel = request == null ? null : request.acquisitionChannel;
        StartResult result = service.startOrResume(userId, channel, correlationId);
        OnboardingProgressResponse body = assembler.toResponse(result.session());

        Response.ResponseBuilder builder = result.created()
                ? Response.status(Response.Status.CREATED)
                : Response.ok();
        return builder.entity(body).header(CorrelationIds.HEADER, correlationId).build();
    }

    private UUID currentUserId() {
        try {
            return UUID.fromString(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            // SUPUESTO: el subject del token es un UUID. Si no lo es, no se puede identificar al usuario.
            throw new NotAuthorizedException("Bearer");
        }
    }

    private static void requireValidIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new OnboardingValidationException(
                    "Falta el encabezado Idempotency-Key.",
                    Map.of(IDEMPOTENCY_HEADER, "Envía un UUID único por intento en el encabezado Idempotency-Key."));
        }
        try {
            UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new OnboardingValidationException(
                    "El encabezado Idempotency-Key no es válido.",
                    Map.of(IDEMPOTENCY_HEADER, "El valor debe ser un UUID válido."));
        }
    }
}