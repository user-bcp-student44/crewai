// PaymentLimitsPort.java
package pe.bcp.login.port;

import java.util.Optional;
import pe.bcp.login.domain.PaymentLimits;

public interface PaymentLimitsPort {

    Optional<PaymentLimits> find(String providerId, String channel);
}