// PaymentHistoryService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import pe.bcp.login.domain.HistoryPage;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentNotFoundException;
import pe.bcp.login.domain.PaymentStatus;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.repository.PaymentRepository;

@ApplicationScoped
public class PaymentHistoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentRepository repository;

    public PaymentHistoryService(PaymentRepository repository) {
        this.repository = repository;
    }

    /** SUPUESTO: el historial lista los pagos terminales (SUCCEEDED, FAILED, EXPIRED) del pagador. */
    public HistoryPage list(String payerId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new PaymentValidationException("PAGINATION_INVALID", "Parámetros de paginación inválidos.");
        }
        List<Payment> items = repository.findTerminalByPayer(payerId, size, (long) page * size);
        long total = repository.countTerminalByPayer(payerId);
        return new HistoryPage(items, page, size, total);
    }

    /** Comprobante: solo disponible para pagos SUCCEEDED (CA3). */
    public Payment receipt(String payerId, UUID paymentId) {
        Payment payment = repository.findByIdAndPayer(paymentId, payerId)
                .orElseThrow(() -> new PaymentNotFoundException("PAYMENT_NOT_FOUND", "No se encontró el pago."));
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            throw new PaymentNotFoundException("RECEIPT_NOT_AVAILABLE",
                    "El comprobante solo está disponible para pagos exitosos.");
        }
        return payment;
    }
}