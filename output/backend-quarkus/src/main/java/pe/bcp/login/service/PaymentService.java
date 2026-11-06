// PaymentService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import pe.bcp.login.model.IdempotencyRecord;
import pe.bcp.login.model.IdempotencyStatus;
import pe.bcp.login.model.PaymentRecord;
import pe.bcp.login.model.PaymentStatus;
import pe.bcp.login.repository.IdempotencyRepository;
import pe.bcp.login.repository.PaymentRecordRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lógica de negocio de pagos con idempotencia obligatoria.
 * Estrategia "insertar primero": se registra la clave en PROCESSING antes de llamar al backend.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern PAYMENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final IdempotencyRepository idempotencyRepository;
    private final PaymentRecordRepository paymentRepository;
    private final WalletBackendPort backendPort;
    private final long maxAmountCents;

    @Inject
    public PaymentService(IdempotencyRepository idempotencyRepository,
                          PaymentRecordRepository paymentRepository,
                          WalletBackendPort backendPort,
                          @ConfigProperty(name = "wallet.payment.max-amount-cents", defaultValue = "50000")
                          long maxAmountCents) {
        this.idempotencyRepository = idempotencyRepository;
        this.paymentRepository = paymentRepository;
        this.backendPort = backendPort;
        this.maxAmountCents = maxAmountCents;
    }

    public CreatePaymentOutcome createPayment(String idempotencyKey, PaymentCommand command) {
        validateKey(idempotencyKey);
        if (command.amountCents() > maxAmountCents) {
            throw new DomainException(
                    ErrorCode.AMOUNT_LIMIT_EXCEEDED,
                    "El importe supera el límite permitido para pagos de bajo monto.",
                    false);
        }

        String requestHash = hashOf(command);
        Instant now = Instant.now();
        String paymentId = UUID.randomUUID().toString();

        boolean inserted = idempotencyRepository.tryInsert(idempotencyKey, requestHash, paymentId, now);
        if (!inserted) {
            return replay(idempotencyKey, requestHash);
        }

        PaymentRecord pending = new PaymentRecord(
                paymentId,
                null,
                command.payerWalletId(),
                command.destination(),
                command.amountCents(),
                command.currency(),
                command.description(),
                PaymentStatus.PENDING,
                null,
                now,
                now);
        paymentRepository.insert(pending);

        PaymentRecord result = callBackend(pending);
        idempotencyRepository.markCompleted(idempotencyKey);
        return new CreatePaymentOutcome(result, false);
    }

    public PaymentRecord getPayment(String paymentId) {
        if (paymentId == null || !PAYMENT_ID_PATTERN.matcher(paymentId).matches()) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "El identificador de pago no es válido.",
                    false);
        }
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PAYMENT_NOT_FOUND,
                        "No se encontró el pago solicitado.",
                        false));
    }

    private CreatePaymentOutcome replay(String idempotencyKey, String requestHash) {
        Optional<IdempotencyRecord> existing = idempotencyRepository.findByKey(idempotencyKey);
        if (existing.isEmpty()) {
            throw inProgress();
        }
        IdempotencyRecord record = existing.get();
        if (!record.requestHash().equals(requestHash)) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "La clave de idempotencia ya fue usada con una solicitud distinta.",
                    false);
        }
        if (record.status() != IdempotencyStatus.COMPLETED || record.paymentId() == null) {
            throw inProgress();
        }
        PaymentRecord payment = paymentRepository.findById(record.paymentId())
                .orElseThrow(PaymentService::inProgress);
        return new CreatePaymentOutcome(payment, true);
    }

    private PaymentRecord callBackend(PaymentRecord pending) {
        BackendPaymentCommand backendCommand = new BackendPaymentCommand(
                pending.paymentId(),
                pending.payerWalletId(),
                pending.destination(),
                pending.amountCents(),
                pending.currency(),
                pending.description());
        try {
            BackendPaymentResult result = backendPort.createPayment(backendCommand);
            PaymentStatus status = (result == null || result.status() == null)
                    ? PaymentStatus.PENDING
                    : result.status();
            String backendPaymentId = result == null ? null : result.backendPaymentId();
            String failureCode = result == null ? null : result.failureCode();
            Instant updatedAt = Instant.now();
            paymentRepository.updateOutcome(
                    pending.paymentId(), status, backendPaymentId, failureCode, updatedAt);
            return pending.withOutcome(status, backendPaymentId, failureCode, updatedAt);
        } catch (BackendUnavailableException e) {
            // Resultado desconocido: se deja PENDING para que la sincronización lo resuelva.
            LOG.warnf("Backend sin respuesta para paymentId=%s; el pago queda PENDING", pending.paymentId());
            return pending;
        }
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "El encabezado Idempotency-Key es obligatorio.",
                    false);
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key debe tener entre 8 y 64 caracteres (letras, números, guion o guion bajo).",
                    false);
        }
    }

    private static DomainException inProgress() {
        return new DomainException(
                ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "La solicitud con esta clave de idempotencia aún se está procesando.",
                true);
    }

    private static String hashOf(PaymentCommand command) {
        String canonical = String.join("|",
                command.payerWalletId(),
                command.destination(),
                Long.toString(command.amountCents()),
                command.currency(),
                command.description() == null ? "" : command.description());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}