// PaymentResource.java
package pe.bcp.login.resource;

import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.HistoryPageResponse;
import pe.bcp.login.dto.PaymentRequest;
import pe.bcp.login.dto.PaymentResponse;
import pe.bcp.login.dto.PreviewResponse;
import pe.bcp.login.dto.ReceiptResponse;
import pe.bcp.login.service.CreatePaymentService;
import pe.bcp.login.service.CreateResult;
import pe.bcp.login.service.GetPaymentService;
import pe.bcp.login.service.PaymentHistoryService;
import pe.bcp.login.service.PreviewPaymentService;

/**
 * SUPUESTO: rutas y codigos de estado propuestos; el contrato de Arquitectura recibido estaba truncado.
 * Capa HTTP: solo valida forma, identifica al pagador y delega. Sin logica de negocio.
 */
@Path("/payments")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PaymentResource {

    private final SecurityIdentity identity;
    private final PreviewPaymentService previewService;
    private final CreatePaymentService createService;
    private final GetPaymentService getService;
    private final PaymentHistoryService historyService;

    public PaymentResource(SecurityIdentity identity, PreviewPaymentService previewService,
            CreatePaymentService createService, GetPaymentService getService,
            PaymentHistoryService historyService) {
        this.identity = identity;
        this.previewService = previewService;
        this.createService = createService;
        this.getService = getService;
        this.historyService = historyService;
    }

    @POST
    @Path("/preview")
    public PreviewResponse preview(@NotNull @Valid PaymentRequest request) {
        payerId();
        return PaymentMapper.toPreviewResponse(previewService.preview(request));
    }

    @POST
    public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey,
            @NotNull @Valid PaymentRequest request) {
        CreateResult result = createService.create(payerId(), idempotencyKey, request);
        PaymentResponse body = PaymentMapper.toPaymentResponse(result.payment());
        Response.Status status = result.created() ? Response.Status.CREATED : Response.Status.OK;
        return Response.status(status).entity(body).build();
    }

    @GET
    @Path("/history")
    public HistoryPageResponse history(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        return PaymentMapper.toHistoryResponse(historyService.list(payerId(), page, size));
    }

    @GET
    @Path("/{id}")
    public PaymentResponse get(@PathParam("id") String id) {
        return PaymentMapper.toPaymentResponse(getService.get(payerId(), parseId(id)));
    }

    @GET
    @Path("/{id}/receipt")
    public ReceiptResponse receipt(@PathParam("id") String id) {
        return PaymentMapper.toReceiptResponse(historyService.receipt(payerId(), parseId(id)));
    }

    /** SUPUESTO: el nombre del principal autenticado identifica al pagador (por ejemplo, subject del token). */
    private String payerId() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                || identity.getPrincipal().getName() == null || identity.getPrincipal().getName().isBlank()) {
            throw new UnauthorizedException();
        }
        return identity.getPrincipal().getName();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new PaymentValidationException("PAYMENT_ID_INVALID", "El identificador del pago no es válido.");
        }
    }
}