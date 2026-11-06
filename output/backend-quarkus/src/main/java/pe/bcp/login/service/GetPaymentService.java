// GetPaymentService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentNotFoundException;
import pe.bcp.login.repository.PaymentRepository;

@ApplicationScoped
public class GetPaymentService {

    private final PaymentRepository repository;
    private final PaymentResolutionService resolution;

    public GetPaymentService(PaymentRepository repository, PaymentResolutionService resolution) {
        this.repository = repository;
        this.resolution = resolution;
    }

    /** Un pago ajeno se reporta como inexistente (404) para no revelar su existencia. */
    public Payment get(String payerId, UUID paymentId) {
        Payment payment = repository.findByIdAndPayer(paymentId, payerId)
                .orElseThrow(() -> new PaymentNotFoundException("PAYMENT_NOT_FOUND", "No se encontró el pago."));
        return resolution.refresh(payment);
    }
}