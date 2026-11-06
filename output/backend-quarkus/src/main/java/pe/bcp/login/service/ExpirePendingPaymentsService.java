// ExpirePendingPaymentsService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.util.List;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.repository.PaymentRepository;

@ApplicationScoped
public class ExpirePendingPaymentsService {

    private static final Logger LOG = Logger.getLogger(ExpirePendingPaymentsService.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository repository;
    private final PaymentResolutionService resolution;
    private final Clock clock;

    public ExpirePendingPaymentsService(PaymentRepository repository, PaymentResolutionService resolution,
            Clock clock) {
        this.repository = repository;
        this.resolution = resolution;
        this.clock = clock;
    }

    /**
     * Resuelve pagos PENDING vencidos. Es seguro con varias instancias gracias al compare-and-set.
     *
     * @return cantidad de pagos que quedaron en estado terminal.
     */
    public int expireDue() {
        List<Payment> due = repository.findExpiredPending(clock.instant(), BATCH_SIZE);
        int closed = 0;
        for (Payment payment : due) {
            try {
                if (resolution.refresh(payment).status().isTerminal()) {
                    closed++;
                }
            } catch (RuntimeException e) {
                LOG.errorf("No se pudo resolver el pago vencido %s", payment.id());
            }
        }
        return closed;
    }
}