// PaymentsConfig.java
package pe.bcp.login.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;

/**
 * Propiedades: payments.limits.&lt;proveedor&gt;.&lt;canal&gt;.currency / min-minor / max-minor,
 * payments.pending-ttl, payments.expiry-check-interval.
 */
@ConfigMapping(prefix = "payments")
public interface PaymentsConfig {

    Map<String, Map<String, LimitConfig>> limits();

    @WithDefault("PT5M")
    Duration pendingTtl();

    @WithDefault("PT30S")
    Duration expiryCheckInterval();

    interface LimitConfig {

        String currency();

        long minMinor();

        long maxMinor();
    }
}