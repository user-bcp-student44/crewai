// PaymentStatusMapper.java
package pe.bcp.login.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Politica de dominio: estado crudo del mock -> PaymentStatus.
 * SUPUESTO: los valores crudos del mock son los listados abajo (contrato del mock no recibido).
 * Cualquier valor desconocido se trata como PENDING (nunca produce un terminal).
 */
public final class PaymentStatusMapper {

    private static final Map<String, PaymentStatus> MAPPING = Map.ofEntries(
            Map.entry("SUCCESS", PaymentStatus.SUCCEEDED),
            Map.entry("APPROVED", PaymentStatus.SUCCEEDED),
            Map.entry("FAILED", PaymentStatus.FAILED),
            Map.entry("REJECTED", PaymentStatus.FAILED),
            Map.entry("DECLINED", PaymentStatus.FAILED),
            Map.entry("ERROR", PaymentStatus.FAILED),
            Map.entry("EXPIRED", PaymentStatus.EXPIRED),
            Map.entry("TIMEOUT", PaymentStatus.EXPIRED),
            Map.entry("PENDING", PaymentStatus.PENDING),
            Map.entry("PROCESSING", PaymentStatus.PENDING));

    private PaymentStatusMapper() {
    }

    public static PaymentStatus map(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return PaymentStatus.PENDING;
        }
        return MAPPING.getOrDefault(rawStatus.trim().toUpperCase(Locale.ROOT), PaymentStatus.PENDING);
    }

    /** Codigo estable propio (no se expone el mensaje crudo del mock). */
    public static String reasonFor(PaymentStatus terminal) {
        return switch (terminal) {
            case FAILED -> "PROVIDER_REJECTED";
            case EXPIRED -> "TIMEOUT";
            default -> null;
        };
    }
}