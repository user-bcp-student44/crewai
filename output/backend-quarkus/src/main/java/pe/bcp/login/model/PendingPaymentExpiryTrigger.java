// PendingPaymentExpiryTrigger.java
package pe.bcp.login.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import pe.bcp.login.service.ExpirePendingPaymentsService;

@ApplicationScoped
public class PendingPaymentExpiryTrigger {

    private final ExpirePendingPaymentsService service;

    public PendingPaymentExpiryTrigger(ExpirePendingPaymentsService service) {
        this.service = service;
    }

    @Scheduled(every = "{payments.expiry-check-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "pending-payment-expiry")
    void run() {
        service.expireDue();
    }
}