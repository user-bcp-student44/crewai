// CdiOnboardingEventPublisher.java
package pe.bcp.login.service;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * Publicador provisional: dispara un evento CDI asíncrono dentro del proceso.
 * El transporte real (broker, outbox, tabla de eventos) está pendiente de definir con Datos (#14).
 */
@ApplicationScoped
public class CdiOnboardingEventPublisher implements OnboardingEventPublisher {

    private static final Logger LOG = Logger.getLogger(CdiOnboardingEventPublisher.class);

    @Inject
    Event<OnboardingEvent> cdiEvent;

    @Override
    public void publish(OnboardingEvent event) {
        cdiEvent.fireAsync(event);
        LOG.infov("Evento {0} emitido para la sesión {1}", event.eventType(), event.sessionId());
    }
}