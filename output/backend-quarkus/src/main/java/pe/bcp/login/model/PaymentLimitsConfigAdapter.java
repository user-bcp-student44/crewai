// PaymentLimitsConfigAdapter.java
package pe.bcp.login.adapter;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import pe.bcp.login.config.PaymentsConfig;
import pe.bcp.login.domain.PaymentLimits;
import pe.bcp.login.port.PaymentLimitsPort;

@ApplicationScoped
public class PaymentLimitsConfigAdapter implements PaymentLimitsPort {

    private final PaymentsConfig config;

    public PaymentLimitsConfigAdapter(PaymentsConfig config) {
        this.config = config;
    }

    @Override
    public Optional<PaymentLimits> find(String providerId, String channel) {
        Map<String, PaymentsConfig.LimitConfig> byChannel = config.limits().get(providerId);
        if (byChannel == null) {
            return Optional.empty();
        }
        PaymentsConfig.LimitConfig limit = byChannel.get(channel);
        if (limit == null) {
            return Optional.empty();
        }
        return Optional.of(new PaymentLimits(providerId, channel, limit.currency(),
                limit.minMinor(), limit.maxMinor()));
    }
}