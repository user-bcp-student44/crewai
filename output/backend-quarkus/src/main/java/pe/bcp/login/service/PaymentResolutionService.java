// PaymentResolutionService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentStatus;
import pe.bcp.login.domain.PaymentStatusMapper;
import pe.bcp.login.port.GatewayResult;
import pe.bcp.login.port.PaymentEventPublisherPort;
import pe.bcp.login.port.PaymentGatewayPort;
import pe.bcp.login.repository.PaymentRepository;

/**
 * Unico punto que lleva un intento a estado terminal (CA2, #16).
 * La transicion es compare-and-set; solo quien la gana publica eventos (CA3, CA6).
 */
@ApplicationScoped
public class PaymentResolutionService {

    private static final Logger LOG = Logger.getLogger(PaymentResolutionService.class);

    private final PaymentRepository repository;
    private final PaymentGatewayPort gateway;
    private final PaymentEventPublisherPort events;
    private final Clock clock;

    public PaymentResolutionService(PaymentRepository repository, PaymentGatewayPort gateway,
            PaymentEventPublisherPort events, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    /** Aplica una respuesta cruda del proveedor. Un estado no terminal o desconocido deja el pago PENDING. */
    public Payment apply(Payment payment, GatewayResult result) {
        PaymentStatus mapped = PaymentStatusMapper.map(result.rawStatus());
        if (!mapped.isTerminal()) {
            return payment;
        }
        if (mapped == PaymentStatus.SUCCEEDED
                && (result.transactionReference() == null || result.transactionReference().isBlank())) {
            LOG.warnf("Pago %s: el proveedor reporta exito sin referencia; se mantiene PENDING", payment.id());
            return payment;
        }
        String reference = mapped == PaymentStatus.SUCCEEDED ? result.transactionReference() : null;
        return finish(payment, mapped, reference);
    }

    /**
     * Consulta el estado al proveedor y, si sigue sin resultado y vencio, lo expira.
     * Si el proveedor no responde NO se expira (el resultado real es desconocido).
     */
    public Payment refresh(Payment payment) {
        if (payment.status().isTerminal()) {
            return payment;
        }
        Payment current;
        try {
            current = apply(payment, gateway.fetchStatus(payment));
        } catch (GatewayUnavailableException e) {
            LOG.warnf("Pago %s: proveedor no disponible al consultar estado", payment.id());
            return payment;
        }
        return expireIfDue(current);
    }

    public void announceCreated(Payment payment) {
        safely(() -> events.publishFunnel("PAYMENT_CREATED", payment), "PAYMENT_CREATED", payment);
    }

    private Payment expireIfDue(Payment payment) {
        if (payment.status().isTerminal() || clock.instant().isBefore(payment.expiresAt())) {
            return payment;
        }
        return finish(payment, PaymentStatus.EXPIRED, null);
    }

    private Payment finish(Payment payment, PaymentStatus terminal, String reference) {
        Instant now = clock.instant();
        boolean won = repository.transitionIfPending(payment.id(), terminal, reference,
                PaymentStatusMapper.reasonFor(terminal), now);
        Payment current = repository.findById(payment.id()).orElse(payment);
        if (won) {
            publishTerminalEvents(current);
        }
        return current;
    }

    private void publishTerminalEvents(Payment payment) {
        if (payment.status() == PaymentStatus.SUCCEEDED) {
            safely(() -> events.publishActivation(payment), "ACTIVATION", payment);
        }
        safely(() -> events.publishFunnel("PAYMENT_" + payment.status().name(), payment),
                "FUNNEL_" + payment.status().name(), payment);
    }

    /** SUPUESTO: la publicacion es best-effort hasta definir outbox (pendiente para Arquitectura/Datos). */
    private void safely(Runnable action, String eventName, Payment payment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.errorf("No se pudo publicar el evento %s del pago %s", eventName, payment.id());
        }
    }
}