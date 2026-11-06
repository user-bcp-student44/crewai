// CreatePaymentService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import pe.bcp.login.config.PaymentsConfig;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentConflictException;
import pe.bcp.login.domain.PaymentPreview;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.PaymentRequest;
import pe.bcp.login.port.PaymentGatewayPort;
import pe.bcp.login.repository.PaymentRepository;

@ApplicationScoped
public class CreatePaymentService {

    private static final Logger LOG = Logger.getLogger(CreatePaymentService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final PreviewPaymentService previewService;
    private final PaymentRepository repository;
    private final PaymentGatewayPort gateway;
    private final PaymentResolutionService resolution;
    private final PaymentsConfig config;
    private final Clock clock;

    public CreatePaymentService(PreviewPaymentService previewService, PaymentRepository repository,
            PaymentGatewayPort gateway, PaymentResolutionService resolution, PaymentsConfig config,
            Clock clock) {
        this.previewService = previewService;
        this.repository = repository;
        this.gateway = gateway;
        this.resolution = resolution;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Crea un intento de pago de forma idempotente (CA4).
     * 1) Repeticion con la misma clave y payload -> mismo intento. Payload distinto -> 409.
     * 2) Revalida limites y destino (D9: el preview no tiene estado).
     * 3) Un solo PENDING por pagador -> 409.
     * 4) Persiste PENDING y lo envia al proveedor.
     */
    public CreateResult create(String payerId, String idempotencyKey, PaymentRequest request) {
        validateKey(idempotencyKey);
        String hash = fingerprint(request);

        Optional<Payment> existing = repository.findByIdempotencyKey(payerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        PaymentPreview preview = previewService.preview(request);

        if (repository.findPendingByPayer(payerId).isPresent()) {
            throw inProgress();
        }

        Payment payment = Payment.newPending(UUID.randomUUID(), payerId, request.providerId(),
                request.channel(), preview.destination(), request.amountMinor(), request.currency(),
                idempotencyKey, hash, clock.instant(), config.pendingTtl());

        if (!repository.insertIfAbsent(payment)) {
            Optional<Payment> raced = repository.findByIdempotencyKey(payerId, idempotencyKey);
            if (raced.isPresent()) {
                return replay(raced.get(), hash);
            }
            throw inProgress();
        }

        resolution.announceCreated(payment);
        return new CreateResult(submitToGateway(payment), true);
    }

    private Payment submitToGateway(Payment payment) {
        try {
            return resolution.apply(payment, gateway.initiate(payment));
        } catch (GatewayUnavailableException e) {
            // Resultado desconocido: el intento queda PENDING y se resuelve por consulta/expiracion.
            LOG.warnf("Pago %s: proveedor no disponible al iniciar; queda PENDING", payment.id());
            return payment;
        }
    }

    private static CreateResult replay(Payment existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new PaymentConflictException("IDEMPOTENCY_KEY_REUSED",
                    "La clave de idempotencia ya se usó con una solicitud distinta.");
        }
        return new CreateResult(existing, false);
    }

    private static PaymentConflictException inProgress() {
        return new PaymentConflictException("PAYMENT_IN_PROGRESS",
                "Ya existe un pago en curso. Espera su resultado antes de iniciar otro.");
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new PaymentValidationException("IDEMPOTENCY_KEY_REQUIRED",
                    "El header Idempotency-Key es obligatorio.");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new PaymentValidationException("IDEMPOTENCY_KEY_INVALID",
                    "El header Idempotency-Key tiene un formato inválido.");
        }
    }

    private static String fingerprint(PaymentRequest r) {
        String canonical = String.join("\u001F", r.providerId(), r.channel(), r.destinationType(),
                r.destinationIdentifier(), Long.toString(r.amountMinor()), r.currency());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible");
        }
    }
}