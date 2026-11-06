// PreviewPaymentService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import pe.bcp.login.domain.AmountPolicy;
import pe.bcp.login.domain.Destination;
import pe.bcp.login.domain.PaymentLimits;
import pe.bcp.login.domain.PaymentNotFoundException;
import pe.bcp.login.domain.PaymentPreview;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.PaymentRequest;
import pe.bcp.login.port.DestinationResolverPort;
import pe.bcp.login.port.PaymentLimitsPort;

@ApplicationScoped
public class PreviewPaymentService {

    private final PaymentLimitsPort limitsPort;
    private final DestinationResolverPort destinationResolver;

    public PreviewPaymentService(PaymentLimitsPort limitsPort, DestinationResolverPort destinationResolver) {
        this.limitsPort = limitsPort;
        this.destinationResolver = destinationResolver;
    }

    /** Valida monto y limites y resuelve la identidad del destino (CA1, #15). Sin estado ni persistencia. */
    public PaymentPreview preview(PaymentRequest request) {
        PaymentLimits limits = limitsPort.find(request.providerId(), request.channel())
                .orElseThrow(() -> new PaymentValidationException("LIMITS_NOT_CONFIGURED",
                        "No hay límites configurados para este proveedor y canal."));
        AmountPolicy.validate(request.amountMinor(), request.currency(), limits);

        Destination destination = destinationResolver
                .resolve(request.providerId(), request.channel(),
                        request.destinationType(), request.destinationIdentifier())
                .orElseThrow(() -> new PaymentNotFoundException("DESTINATION_NOT_FOUND",
                        "No se encontró el destino indicado."));

        return new PaymentPreview(request.providerId(), request.channel(), destination,
                request.amountMinor(), request.currency());
    }
}