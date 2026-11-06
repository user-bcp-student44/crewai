// OnboardingService.java
package pe.bcp.login.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.bcp.login.exception.DuplicateSessionException;
import pe.bcp.login.exception.StorageException;
import pe.bcp.login.model.OnboardingSession;
import pe.bcp.login.model.OnboardingStatus;
import pe.bcp.login.model.StepDefinition;
import pe.bcp.login.model.WalletStatus;
import pe.bcp.login.repository.OnboardingSessionRepository;

@ApplicationScoped
public class OnboardingService {

    private static final Logger LOG = Logger.getLogger(OnboardingService.class);
    private static final int FIRST_STEP = 1;
    private static final int INITIAL_VERSION = 1;

    @Inject
    OnboardingSessionRepository repository;

    @Inject
    OnboardingStepCatalog catalog;

    @Inject
    OnboardingEventPublisher eventPublisher;

    /**
     * Inicia o reanuda el onboarding del usuario (E1).
     * Reanudar es idempotente por naturaleza: devuelve la sesión IN_PROGRESS existente sin evento nuevo.
     * La Idempotency-Key se valida en la capa HTTP; su persistencia queda pendiente (ver supuestos).
     */
    public StartResult startOrResume(UUID userId, String acquisitionChannel, String correlationId) {
        Optional<OnboardingSession> existing = repository.findInProgressByUserId(userId);
        if (existing.isPresent()) {
            return new StartResult(existing.get(), false);
        }

        OnboardingSession fresh = newSession(userId, normalize(acquisitionChannel));
        try {
            repository.insert(fresh);
        } catch (DuplicateSessionException race) {
            // Otra petición concurrente creó la sesión: se reanuda esa, sin evento de inicio.
            OnboardingSession winner = repository.findInProgressByUserId(userId)
                    .orElseThrow(() -> new StorageException("Estado inconsistente tras conflicto de sesión"));
            return new StartResult(winner, false);
        }

        publishStarted(fresh, correlationId);
        return new StartResult(fresh, true);
    }

    private OnboardingSession newSession(UUID userId, String acquisitionChannel) {
        Instant now = Instant.now();
        return new OnboardingSession(
                UUID.randomUUID(),
                userId,
                OnboardingStatus.IN_PROGRESS,
                FIRST_STEP,
                INITIAL_VERSION,
                WalletStatus.PENDING,
                acquisitionChannel,
                now,
                now);
    }

    private void publishStarted(OnboardingSession session, String correlationId) {
        try {
            String firstStepId = catalog.byOrder(FIRST_STEP).map(StepDefinition::stepId).orElse(null);
            eventPublisher.publish(new OnboardingEvent(
                    UUID.randomUUID(),
                    OnboardingEvent.ONBOARDING_STARTED,
                    session.id(),
                    session.userId(),
                    firstStepId,
                    session.acquisitionChannel(),
                    session.startedAt(),
                    correlationId));
        } catch (RuntimeException e) {
            // La sesión ya está persistida; no se rompe el flujo del usuario por un fallo de analítica.
            // Riesgo: evento perdido. Pendiente: patrón outbox con Datos.
            LOG.errorv(e, "No se pudo emitir onboarding_started para la sesión {0}", session.id());
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}