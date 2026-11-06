# backend_spec.md

> **Alcance de lo que pude revisar.** El contrato de Arquitectura que recibí está **truncado**: veo la estructura hexagonal propuesta y las decisiones D1–D9, pero no las rutas exactas, los códigos de estado ni el formato de errores. No recibí el modelo de Datos ni el código existente del BFF. Todo lo que dependa de eso está marcado como **SUPUESTO** en la sección 6 y debe contrastarse antes de integrar. No generé migraciones ni redefiní tablas.
>
> **Nada fue compilado ni ejecutado.** Todo está **NO EJECUTADO** (sección 5).

---

## 1) Resumen de la implementación

Se implementa el flujo de pago de bajo monto en Java/Quarkus con capas separadas: `resource` (HTTP), `service` (negocio), `repository` (persistencia) y DTOs.

| Endpoint (SUPUESTO de ruta) | Función | Tarea |
|---|---|---|
| `POST /payments/preview` | Valida monto y límites por proveedor/canal y devuelve destino y monto | #15 |
| `POST /payments` (header `Idempotency-Key`) | Crea el intento de forma idempotente. Responde 201 si es nuevo y 200 si es repetición | #15, #16 |
| `GET /payments/{id}` | Consulta el estado. Resuelve el terminal único (`SUCCEEDED`, `FAILED` o `EXPIRED`) | #16 |
| `GET /payments/history?page&size` | Historial paginado de pagos terminales del pagador | #18 |
| `GET /payments/{id}/receipt` | Comprobante, solo para pagos `SUCCEEDED` | #18 |

Decisiones de implementación:

- **Terminal único (D1, D2).** La transición se hace con un `UPDATE ... WHERE id = ? AND status = 'PENDING'` (compare-and-set). Solo quien gana la transición publica eventos.
- **Mapeo del mock (D3).** `PaymentStatusMapper` es una clase de dominio. Un estado desconocido se trata como `PENDING`, nunca como terminal.
- **Idempotencia (D4).**
  - Se usa `Idempotency-Key` por pagador con una huella SHA-256 del payload.
  - La misma clave con el mismo payload devuelve el mismo intento.
  - La misma clave con distinto payload devuelve 409.
- **Sin cobros duplicados (D5).**
  - No se admite un segundo intento mientras haya uno `PENDING` del mismo pagador (409).
  - Reintentar tras un estado terminal crea un intento nuevo con una clave nueva.
- **Límites (D6).** Se leen de configuración (`payments.limits.<proveedor>.<canal>.*`) a través de `PaymentLimitsPort`. No hay valores fijos en el código.
- **Eventos (D7).** Se publican por `PaymentEventPublisherPort` solo cuando la transición a terminal fue ganada. Los fallos de publicación se registran y no rompen el flujo. La consistencia fuerte (outbox) queda **pendiente**.
- **Expiración.** Se resuelve de forma perezosa en `GET /payments/{id}` y de forma programada con `PendingPaymentExpiryTrigger`. Antes de expirar se consulta al proveedor. Si el proveedor no responde, **no** se expira.
- **Errores.** Se traducen a 400, 404, 409, 503 y 500 con un cuerpo `ErrorResponse` genérico, sin trazas ni SQL. Los 401/403 los resuelve Quarkus con `@Authenticated`.
- **Seguridad de datos.**
  - Todas las consultas son parametrizadas.
  - No hay secretos en el código.
  - `failureReason` es un código estable propio, nunca el mensaje crudo del mock.

**Código no incluido (bloqueado por contrato faltante).** Faltan los adaptadores de `PaymentGatewayPort` (cliente del mock), `DestinationResolverPort` y `PaymentEventPublisherPort`. Solo existen sus puertos. Hasta que existan, la aplicación **no arranca** (CDI: dependencias no satisfechas). Es intencional, para no inventar contratos del mock ni del bus de eventos.

---

## 2) Clases y responsabilidades

Los paquetes parten de `pe.bcp.login`. Las clases de la capa de servicio usan `pe.bcp.login.service`. El resto va en subpaquetes propios para respetar la separación de capas (ver supuesto S1).

| Paquete | Clase | Responsabilidad |
|---|---|---|
| `resource` | `PaymentResource` | Endpoints REST, autenticación (`@Authenticated`), validación de forma, header de idempotencia y códigos 200/201 |
| `resource` | `PaymentMapper` | Dominio → DTOs de salida |
| `resource` | `PaymentExceptionMapper` | Excepciones de negocio → HTTP (400, 404, 409, 503, 500) |
| `resource` | `ConstraintViolationExceptionMapper` | Violaciones de Bean Validation → 400 con `fieldErrors` |
| `resource` | `UnexpectedExceptionMapper` | JSON inválido → 400, `WebApplicationException` se conserva, el resto → 500 genérico |
| `dto` | `PaymentRequest` | Entrada de preview y create, con validaciones |
| `dto` | `DestinationDto`, `PreviewResponse`, `PaymentResponse`, `HistoryPageResponse`, `ReceiptResponse`, `ErrorResponse` | Salidas |
| `service` | `PreviewPaymentService` | Límites, `AmountPolicy` y resolución del destino (CA1) |
| `service` | `CreatePaymentService` | Idempotencia, intento `PENDING` único, persistencia, envío al proveedor |
| `service` | `CreateResult` | Resultado de create (pago y bandera `created`) |
| `service` | `PaymentResolutionService` | Mapeo del estado, transición condicional, expiración y eventos |
| `service` | `GetPaymentService` | Consulta y refresco del estado |
| `service` | `PaymentHistoryService` | Historial y comprobante |
| `service` | `ExpirePendingPaymentsService` | Cierre en lote de pagos vencidos |
| `scheduler` | `PendingPaymentExpiryTrigger` | Disparador programado |
| `repository` | `PaymentRepository` | JDBC parametrizado: insert, búsquedas, transición condicional, historial |
| `domain` | `Payment`, `PaymentStatus`, `Destination`, `PaymentLimits`, `PaymentPreview`, `HistoryPage` | Modelo |
| `domain` | `PaymentStatusMapper`, `AmountPolicy` | Reglas puras |
| `domain` | `PaymentException` y subclases | Errores tipados |
| `port` | `PaymentGatewayPort`, `GatewayResult`, `DestinationResolverPort`, `PaymentLimitsPort`, `PaymentEventPublisherPort` | Puertos de salida |
| `adapter` | `PaymentLimitsConfigAdapter` | Límites desde configuración |
| `config` | `PaymentsConfig`, `ClockProducer` | Configuración y reloj |

### Código Java

```java
// PaymentStatus.java
package pe.bcp.login.domain;

public enum PaymentStatus {
    PENDING(false),
    SUCCEEDED(true),
    FAILED(true),
    EXPIRED(true);

    private final boolean terminal;

    PaymentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
```

```java
// Destination.java
package pe.bcp.login.domain;

public record Destination(String type, String identifier, String displayName) {
}
```

```java
// PaymentLimits.java
package pe.bcp.login.domain;

public record PaymentLimits(String providerId, String channel, String currency, long minMinor, long maxMinor) {
}
```

```java
// Payment.java
package pe.bcp.login.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Payment(
        UUID id,
        String payerId,
        String providerId,
        String channel,
        Destination destination,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String transactionReference,
        String failureReason,
        String idempotencyKey,
        String requestHash,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt) {

    public static Payment newPending(
            UUID id,
            String payerId,
            String providerId,
            String channel,
            Destination destination,
            long amountMinor,
            String currency,
            String idempotencyKey,
            String requestHash,
            Instant now,
            Duration ttl) {
        return new Payment(id, payerId, providerId, channel, destination, amountMinor, currency,
                PaymentStatus.PENDING, null, null, idempotencyKey, requestHash, now, now.plus(ttl), null);
    }
}
```

```java
// PaymentPreview.java
package pe.bcp.login.domain;

public record PaymentPreview(
        String providerId,
        String channel,
        Destination destination,
        long amountMinor,
        String currency) {
}
```

```java
// HistoryPage.java
package pe.bcp.login.domain;

import java.util.List;

public record HistoryPage(List<Payment> items, int page, int size, long totalElements) {
}
```

```java
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
```

```java
// AmountPolicy.java
package pe.bcp.login.domain;

public final class AmountPolicy {

    private AmountPolicy() {
    }

    public static void validate(long amountMinor, String currency, PaymentLimits limits) {
        if (amountMinor <= 0) {
            throw new PaymentValidationException("AMOUNT_INVALID", "El monto debe ser mayor que cero.");
        }
        if (limits.currency() == null || !limits.currency().equals(currency)) {
            throw new PaymentValidationException("CURRENCY_NOT_SUPPORTED",
                    "La moneda no está soportada para este proveedor y canal.");
        }
        if (amountMinor < limits.minMinor() || amountMinor > limits.maxMinor()) {
            throw new PaymentValidationException("AMOUNT_OUT_OF_RANGE",
                    "El monto está fuera del rango permitido para este proveedor y canal.");
        }
    }
}
```

```java
// PaymentException.java
package pe.bcp.login.domain;

public abstract class PaymentException extends RuntimeException {

    private final String code;

    protected PaymentException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

```java
// PaymentValidationException.java
package pe.bcp.login.domain;

/** Error de validacion de entrada o de regla de negocio -> HTTP 400. */
public class PaymentValidationException extends PaymentException {

    public PaymentValidationException(String code, String message) {
        super(code, message);
    }
}
```

```java
// PaymentNotFoundException.java
package pe.bcp.login.domain;

/** Recurso inexistente (o ajeno al pagador) -> HTTP 404. */
public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(String code, String message) {
        super(code, message);
    }
}
```

```java
// PaymentConflictException.java
package pe.bcp.login.domain;

/** Duplicado o conflicto de estado -> HTTP 409. */
public class PaymentConflictException extends PaymentException {

    public PaymentConflictException(String code, String message) {
        super(code, message);
    }
}
```

```java
// GatewayUnavailableException.java
package pe.bcp.login.domain;

/** El proveedor/mock no respondio de forma definitiva. El resultado del pago es desconocido. */
public class GatewayUnavailableException extends PaymentException {

    public GatewayUnavailableException() {
        super("PAYMENT_PROVIDER_UNAVAILABLE", "El proveedor de pagos no está disponible en este momento.");
    }
}
```

```java
// PersistenceFailureException.java
package pe.bcp.login.domain;

/** Fallo de persistencia. No transporta SQL ni datos del fallo. */
public class PersistenceFailureException extends PaymentException {

    public PersistenceFailureException() {
        super("INTERNAL_ERROR", "Ocurrió un error inesperado. Intenta nuevamente más tarde.");
    }
}
```

```java
// GatewayResult.java
package pe.bcp.login.port;

/** Respuesta cruda del proveedor/mock. El estado terminal lo decide PaymentStatusMapper. */
public record GatewayResult(String rawStatus, String transactionReference) {
}
```

```java
// PaymentGatewayPort.java
package pe.bcp.login.port;

import pe.bcp.login.domain.Payment;

/**
 * Puerto hacia el proveedor/mock. Debe usar payment.id() como referencia idempotente hacia el proveedor.
 * Debe lanzar GatewayUnavailableException ante timeout o error de red (resultado desconocido).
 * ADAPTADOR PENDIENTE: el contrato del mock no fue recibido.
 */
public interface PaymentGatewayPort {

    GatewayResult initiate(Payment payment);

    GatewayResult fetchStatus(Payment payment);
}
```

```java
// DestinationResolverPort.java
package pe.bcp.login.port;

import java.util.Optional;
import pe.bcp.login.domain.Destination;

/** ADAPTADOR PENDIENTE: la fuente de la identidad visible del destino no fue especificada. */
public interface DestinationResolverPort {

    Optional<Destination> resolve(String providerId, String channel, String destinationType, String identifier);
}
```

```java
// PaymentLimitsPort.java
package pe.bcp.login.port;

import java.util.Optional;
import pe.bcp.login.domain.PaymentLimits;

public interface PaymentLimitsPort {

    Optional<PaymentLimits> find(String providerId, String channel);
}
```

```java
// PaymentEventPublisherPort.java
package pe.bcp.login.port;

import pe.bcp.login.domain.Payment;

/** ADAPTADOR PENDIENTE: el destino/formato de eventos (activacion MAU, funnel) no fue especificado. */
public interface PaymentEventPublisherPort {

    /** Evento de activacion usado para MAU. Solo para pagos SUCCEEDED. */
    void publishActivation(Payment payment);

    void publishFunnel(String eventName, Payment payment);
}
```

```java
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
```

```java
// ClockProducer.java
package pe.bcp.login.config;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

public class ClockProducer {

    @Produces
    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

```java
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
```

```java
// PaymentRepository.java
package pe.bcp.login.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.Destination;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentStatus;
import pe.bcp.login.domain.PersistenceFailureException;

/**
 * Acceso a datos por JDBC con consultas parametrizadas.
 * SUPUESTO: tabla "payment" con las columnas de COLUMNS (PostgreSQL, timestamptz, uuid).
 * Debe contrastarse con el modelo de Datos aprobado; esta clase NO crea ni modifica esquema.
 * SUPUESTO: Datos define unicidad (payer_id, idempotency_key) y un unico PENDING por pagador;
 * aqui se detecta cualquier violacion de unicidad (SQLState 23505) sin depender del nombre de la restriccion.
 */
@ApplicationScoped
public class PaymentRepository {

    private static final Logger LOG = Logger.getLogger(PaymentRepository.class);
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS = "id, payer_id, provider_id, channel, destination_type, "
            + "destination_identifier, destination_display_name, amount_minor, currency, status, "
            + "transaction_reference, failure_reason, idempotency_key, request_hash, "
            + "created_at, expires_at, completed_at";

    private static final String INSERT = "INSERT INTO payment (id, payer_id, provider_id, channel, "
            + "destination_type, destination_identifier, destination_display_name, amount_minor, currency, "
            + "status, idempotency_key, request_hash, created_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID_AND_PAYER =
            "SELECT " + COLUMNS + " FROM payment WHERE id = ? AND payer_id = ?";

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM payment WHERE id = ?";

    private static final String SELECT_BY_KEY =
            "SELECT " + COLUMNS + " FROM payment WHERE payer_id = ? AND idempotency_key = ?";

    private static final String SELECT_PENDING_BY_PAYER =
            "SELECT " + COLUMNS + " FROM payment WHERE payer_id = ? AND status = 'PENDING' LIMIT 1";

    private static final String SELECT_EXPIRED_PENDING = "SELECT " + COLUMNS
            + " FROM payment WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at ASC LIMIT ?";

    private static final String SELECT_HISTORY = "SELECT " + COLUMNS
            + " FROM payment WHERE payer_id = ? AND status <> 'PENDING' "
            + "ORDER BY completed_at DESC, id DESC LIMIT ? OFFSET ?";

    private static final String COUNT_HISTORY =
            "SELECT COUNT(*) FROM payment WHERE payer_id = ? AND status <> 'PENDING'";

    private static final String TRANSITION = "UPDATE payment SET status = ?, transaction_reference = ?, "
            + "failure_reason = ?, completed_at = ? WHERE id = ? AND status = 'PENDING'";

    private final DataSource dataSource;

    public PaymentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return false si una restriccion de unicidad rechazo el insert. */
    public boolean insertIfAbsent(Payment p) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(INSERT)) {
            int i = 1;
            ps.setObject(i++, p.id());
            ps.setString(i++, p.payerId());
            ps.setString(i++, p.providerId());
            ps.setString(i++, p.channel());
            ps.setString(i++, p.destination().type());
            ps.setString(i++, p.destination().identifier());
            ps.setString(i++, p.destination().displayName());
            ps.setLong(i++, p.amountMinor());
            ps.setString(i++, p.currency());
            ps.setString(i++, p.status().name());
            ps.setString(i++, p.idempotencyKey());
            ps.setString(i++, p.requestHash());
            ps.setObject(i++, toOffset(p.createdAt()));
            ps.setObject(i, toOffset(p.expiresAt()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw fail("insert", e);
        }
    }

    public Optional<Payment> findByIdAndPayer(UUID id, String payerId) {
        return queryList(SELECT_BY_ID_AND_PAYER, ps -> {
            ps.setObject(1, id);
            ps.setString(2, payerId);
        }, PaymentRepository::mapPayment).stream().findFirst();
    }

    public Optional<Payment> findById(UUID id) {
        return queryList(SELECT_BY_ID, ps -> ps.setObject(1, id), PaymentRepository::mapPayment)
                .stream().findFirst();
    }

    public Optional<Payment> findByIdempotencyKey(String payerId, String idempotencyKey) {
        return queryList(SELECT_BY_KEY, ps -> {
            ps.setString(1, payerId);
            ps.setString(2, idempotencyKey);
        }, PaymentRepository::mapPayment).stream().findFirst();
    }

    public Optional<Payment> findPendingByPayer(String payerId) {
        return queryList(SELECT_PENDING_BY_PAYER, ps -> ps.setString(1, payerId),
                PaymentRepository::mapPayment).stream().findFirst();
    }

    public List<Payment> findExpiredPending(Instant now, int limit) {
        return queryList(SELECT_EXPIRED_PENDING, ps -> {
            ps.setObject(1, toOffset(now));
            ps.setInt(2, limit);
        }, PaymentRepository::mapPayment);
    }

    public List<Payment> findTerminalByPayer(String payerId, int limit, long offset) {
        return queryList(SELECT_HISTORY, ps -> {
            ps.setString(1, payerId);
            ps.setInt(2, limit);
            ps.setLong(3, offset);
        }, PaymentRepository::mapPayment);
    }

    public long countTerminalByPayer(String payerId) {
        List<Long> result = queryList(COUNT_HISTORY, ps -> ps.setString(1, payerId), rs -> rs.getLong(1));
        return result.isEmpty() ? 0L : result.get(0);
    }

    /**
     * Compare-and-set: solo transiciona si el pago sigue PENDING.
     *
     * @return true si esta llamada gano la transicion.
     */
    public boolean transitionIfPending(UUID id, PaymentStatus terminal, String transactionReference,
            String failureReason, Instant completedAt) {
        if (terminal == null || !terminal.isTerminal()) {
            throw new IllegalArgumentException("El estado destino debe ser terminal.");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(TRANSITION)) {
            ps.setString(1, terminal.name());
            ps.setString(2, transactionReference);
            ps.setString(3, failureReason);
            ps.setObject(4, toOffset(completedAt));
            ps.setObject(5, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw fail("transition", e);
        }
    }

    private <T> List<T> queryList(String sql, Binder binder, RowReader<T> reader) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(reader.read(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw fail("query", e);
        }
    }

    private static Payment mapPayment(ResultSet rs) throws SQLException {
        Destination destination = new Destination(
                rs.getString("destination_type"),
                rs.getString("destination_identifier"),
                rs.getString("destination_display_name"));
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getString("payer_id"),
                rs.getString("provider_id"),
                rs.getString("channel"),
                destination,
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("transaction_reference"),
                rs.getString("failure_reason"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                toInstant(rs, "created_at"),
                toInstant(rs, "expires_at"),
                toInstant(rs, "completed_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Solo registra la operacion y el SQLState: el mensaje del driver puede contener datos. */
    private static PersistenceFailureException fail(String operation, SQLException e) {
        LOG.errorf("Fallo de persistencia en %s (sqlState=%s)", operation, e.getSQLState());
        return new PersistenceFailureException();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }
}
```

```java
// PaymentRequest.java
package pe.bcp.login.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Entrada comun de preview y create. Monto en unidades menores (long) segun Money del contrato. */
public record PaymentRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String providerId,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String channel,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "formato inválido")
        String destinationType,

        @NotBlank(message = "es obligatorio")
        @Size(max = 64, message = "longitud máxima 64")
        String destinationIdentifier,

        @Positive(message = "debe ser mayor que cero")
        long amountMinor,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "^[A-Z]{3}$", message = "debe ser un código de 3 letras mayúsculas")
        String currency) {
}
```

```java
// DestinationDto.java
package pe.bcp.login.dto;

public record DestinationDto(String type, String identifier, String displayName) {
}
```

```java
// PreviewResponse.java
package pe.bcp.login.dto;

public record PreviewResponse(
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency) {
}
```

```java
// PaymentResponse.java
package pe.bcp.login.dto;

import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String status,
        boolean terminal,
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency,
        String transactionReference,
        String failureReason,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt) {
}
```

```java
// HistoryPageResponse.java
package pe.bcp.login.dto;

import java.util.List;

public record HistoryPageResponse(List<PaymentResponse> items, int page, int size, long totalElements) {
}
```

```java
// ReceiptResponse.java
package pe.bcp.login.dto;

import java.time.Instant;

public record ReceiptResponse(
        String paymentId,
        String transactionReference,
        String providerId,
        String channel,
        DestinationDto destination,
        long amountMinor,
        String currency,
        Instant completedAt) {
}
```

```java
// ErrorResponse.java
package pe.bcp.login.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** SUPUESTO: formato de error {code, message, fieldErrors?}; el formato del contrato no fue visible. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

    public ErrorResponse(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}
```

```java
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
```

```java
// CreateResult.java
package pe.bcp.login.service;

import pe.bcp.login.domain.Payment;

/** created = true si se creo un intento nuevo; false si fue una repeticion idempotente. */
public record CreateResult(Payment payment, boolean created) {
}
```

```java
// PaymentResolutionService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentStatus;
import pe.bcp.login.domain.PaymentStatusMapper;
import pe.bcp.login.port.GatewayResult;
import pe.bcp.login.port.PaymentEventPublisherPort;
import pe.bcp.login.port.PaymentGatewayPort;
import pe.bcp.login.repository.PaymentRepository;

/**
 * Unico punto que lleva un intento a estado terminal (CA2, #16).
 * La transicion es compare-and-set; solo quien la gana publica eventos (CA3, CA6).
 */
@ApplicationScoped
public class PaymentResolutionService {

    private static final Logger LOG = Logger.getLogger(PaymentResolutionService.class);

    private final PaymentRepository repository;
    private final PaymentGatewayPort gateway;
    private final PaymentEventPublisherPort events;
    private final Clock clock;

    public PaymentResolutionService(PaymentRepository repository, PaymentGatewayPort gateway,
            PaymentEventPublisherPort events, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    /** Aplica una respuesta cruda del proveedor. Un estado no terminal o desconocido deja el pago PENDING. */
    public Payment apply(Payment payment, GatewayResult result) {
        PaymentStatus mapped = PaymentStatusMapper.map(result.rawStatus());
        if (!mapped.isTerminal()) {
            return payment;
        }
        if (mapped == PaymentStatus.SUCCEEDED
                && (result.transactionReference() == null || result.transactionReference().isBlank())) {
            LOG.warnf("Pago %s: el proveedor reporta exito sin referencia; se mantiene PENDING", payment.id());
            return payment;
        }
        String reference = mapped == PaymentStatus.SUCCEEDED ? result.transactionReference() : null;
        return finish(payment, mapped, reference);
    }

    /**
     * Consulta el estado al proveedor y, si sigue sin resultado y vencio, lo expira.
     * Si el proveedor no responde NO se expira (el resultado real es desconocido).
     */
    public Payment refresh(Payment payment) {
        if (payment.status().isTerminal()) {
            return payment;
        }
        Payment current;
        try {
            current = apply(payment, gateway.fetchStatus(payment));
        } catch (GatewayUnavailableException e) {
            LOG.warnf("Pago %s: proveedor no disponible al consultar estado", payment.id());
            return payment;
        }
        return expireIfDue(current);
    }

    public void announceCreated(Payment payment) {
        safely(() -> events.publishFunnel("PAYMENT_CREATED", payment), "PAYMENT_CREATED", payment);
    }

    private Payment expireIfDue(Payment payment) {
        if (payment.status().isTerminal() || clock.instant().isBefore(payment.expiresAt())) {
            return payment;
        }
        return finish(payment, PaymentStatus.EXPIRED, null);
    }

    private Payment finish(Payment payment, PaymentStatus terminal, String reference) {
        Instant now = clock.instant();
        boolean won = repository.transitionIfPending(payment.id(), terminal, reference,
                PaymentStatusMapper.reasonFor(terminal), now);
        Payment current = repository.findById(payment.id()).orElse(payment);
        if (won) {
            publishTerminalEvents(current);
        }
        return current;
    }

    private void publishTerminalEvents(Payment payment) {
        if (payment.status() == PaymentStatus.SUCCEEDED) {
            safely(() -> events.publishActivation(payment), "ACTIVATION", payment);
        }
        safely(() -> events.publishFunnel("PAYMENT_" + payment.status().name(), payment),
                "FUNNEL_" + payment.status().name(), payment);
    }

    /** SUPUESTO: la publicacion es best-effort hasta definir outbox (pendiente para Arquitectura/Datos). */
    private void safely(Runnable action, String eventName, Payment payment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.errorf("No se pudo publicar el evento %s del pago %s", eventName, payment.id());
        }
    }
}
```

```java
// CreatePaymentService.java
package pe.bcp.login.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import pe.bcp.login.config.PaymentsConfig;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentConflictException;
import pe.bcp.login.domain.PaymentPreview;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.PaymentRequest;
import pe.bcp.login.port.PaymentGatewayPort;
import pe.bcp.login.repository.PaymentRepository;

@ApplicationScoped
public class CreatePaymentService {

    private static final Logger LOG = Logger.getLogger(CreatePaymentService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final PreviewPaymentService previewService;
    private final PaymentRepository repository;
    private final PaymentGatewayPort gateway;
    private final PaymentResolutionService resolution;
    private final PaymentsConfig config;
    private final Clock clock;

    public CreatePaymentService(PreviewPaymentService previewService, PaymentRepository repository,
            PaymentGatewayPort gateway, PaymentResolutionService resolution, PaymentsConfig config,
            Clock clock) {
        this.previewService = previewService;
        this.repository = repository;
        this.gateway = gateway;
        this.resolution = resolution;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Crea un intento de pago de forma idempotente (CA4).
     * 1) Repeticion con la misma clave y payload -> mismo intento. Payload distinto -> 409.
     * 2) Revalida limites y destino (D9: el preview no tiene estado).
     * 3) Un solo PENDING por pagador -> 409.
     * 4) Persiste PENDING y lo envia al proveedor.
     */
    public CreateResult create(String payerId, String idempotencyKey, PaymentRequest request) {
        validateKey(idempotencyKey);
        String hash = fingerprint(request);

        Optional<Payment> existing = repository.findByIdempotencyKey(payerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        PaymentPreview preview = previewService.preview(request);

        if (repository.findPendingByPayer(payerId).isPresent()) {
            throw inProgress();
        }

        Payment payment = Payment.newPending(UUID.randomUUID(), payerId, request.providerId(),
                request.channel(), preview.destination(), request.amountMinor(), request.currency(),
                idempotencyKey, hash, clock.instant(), config.pendingTtl());

        if (!repository.insertIfAbsent(payment)) {
            Optional<Payment> raced = repository.findByIdempotencyKey(payerId, idempotencyKey);
            if (raced.isPresent()) {
                return replay(raced.get(), hash);
            }
            throw inProgress();
        }

        resolution.announceCreated(payment);
        return new CreateResult(submitToGateway(payment), true);
    }

    private Payment submitToGateway(Payment payment) {
        try {
            return resolution.apply(payment, gateway.initiate(payment));
        } catch (GatewayUnavailableException e) {
            // Resultado desconocido: el intento queda PENDING y se resuelve por consulta/expiracion.
            LOG.warnf("Pago %s: proveedor no disponible al iniciar; queda PENDING", payment.id());
            return payment;
        }
    }

    private static CreateResult replay(Payment existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new PaymentConflictException("IDEMPOTENCY_KEY_REUSED",
                    "La clave de idempotencia ya se usó con una solicitud distinta.");
        }
        return new CreateResult(existing, false);
    }

    private static PaymentConflictException inProgress() {
        return new PaymentConflictException("PAYMENT_IN_PROGRESS",
                "Ya existe un pago en curso. Espera su resultado antes de iniciar otro.");
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new PaymentValidationException("IDEMPOTENCY_KEY_REQUIRED",
                    "El header Idempotency-Key es obligatorio.");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new PaymentValidationException("IDEMPOTENCY_KEY_INVALID",
                    "El header Idempotency-Key tiene un formato inválido.");
        }
    }

    private static String fingerprint(PaymentRequest r) {
        String canonical = String.join("\u001F", r.providerId(), r.channel(), r.destinationType(),
                r.destinationIdentifier(), Long.toString(r.amountMinor()), r.currency());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible");
        }
    }
}
```

```java
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
```

```java
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
```

```java
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
```

```java
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
```

```java
// PaymentMapper.java
package pe.bcp.login.resource;

import java.util.List;
import pe.bcp.login.domain.Destination;
import pe.bcp.login.domain.HistoryPage;
import pe.bcp.login.domain.Payment;
import pe.bcp.login.domain.PaymentPreview;
import pe.bcp.login.dto.DestinationDto;
import pe.bcp.login.dto.HistoryPageResponse;
import pe.bcp.login.dto.PaymentResponse;
import pe.bcp.login.dto.PreviewResponse;
import pe.bcp.login.dto.ReceiptResponse;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    static DestinationDto toDestinationDto(Destination d) {
        return new DestinationDto(d.type(), d.identifier(), d.displayName());
    }

    public static PreviewResponse toPreviewResponse(PaymentPreview p) {
        return new PreviewResponse(p.providerId(), p.channel(), toDestinationDto(p.destination()),
                p.amountMinor(), p.currency());
    }

    public static PaymentResponse toPaymentResponse(Payment p) {
        return new PaymentResponse(
                p.id().toString(),
                p.status().name(),
                p.status().isTerminal(),
                p.providerId(),
                p.channel(),
                toDestinationDto(p.destination()),
                p.amountMinor(),
                p.currency(),
                p.transactionReference(),
                p.failureReason(),
                p.createdAt(),
                p.expiresAt(),
                p.completedAt());
    }

    public static ReceiptResponse toReceiptResponse(Payment p) {
        return new ReceiptResponse(
                p.id().toString(),
                p.transactionReference(),
                p.providerId(),
                p.channel(),
                toDestinationDto(p.destination()),
                p.amountMinor(),
                p.currency(),
                p.completedAt());
    }

    public static HistoryPageResponse toHistoryResponse(HistoryPage page) {
        List<PaymentResponse> items = page.items().stream().map(PaymentMapper::toPaymentResponse).toList();
        return new HistoryPageResponse(items, page.page(), page.size(), page.totalElements());
    }
}
```

```java
// PaymentResource.java
package pe.bcp.login.resource;

import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.HistoryPageResponse;
import pe.bcp.login.dto.PaymentRequest;
import pe.bcp.login.dto.PaymentResponse;
import pe.bcp.login.dto.PreviewResponse;
import pe.bcp.login.dto.ReceiptResponse;
import pe.bcp.login.service.CreatePaymentService;
import pe.bcp.login.service.CreateResult;
import pe.bcp.login.service.GetPaymentService;
import pe.bcp.login.service.PaymentHistoryService;
import pe.bcp.login.service.PreviewPaymentService;

/**
 * SUPUESTO: rutas y codigos de estado propuestos; el contrato de Arquitectura recibido estaba truncado.
 * Capa HTTP: solo valida forma, identifica al pagador y delega. Sin logica de negocio.
 */
@Path("/payments")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PaymentResource {

    private final SecurityIdentity identity;
    private final PreviewPaymentService previewService;
    private final CreatePaymentService createService;
    private final GetPaymentService getService;
    private final PaymentHistoryService historyService;

    public PaymentResource(SecurityIdentity identity, PreviewPaymentService previewService,
            CreatePaymentService createService, GetPaymentService getService,
            PaymentHistoryService historyService) {
        this.identity = identity;
        this.previewService = previewService;
        this.createService = createService;
        this.getService = getService;
        this.historyService = historyService;
    }

    @POST
    @Path("/preview")
    public PreviewResponse preview(@NotNull @Valid PaymentRequest request) {
        payerId();
        return PaymentMapper.toPreviewResponse(previewService.preview(request));
    }

    @POST
    public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey,
            @NotNull @Valid PaymentRequest request) {
        CreateResult result = createService.create(payerId(), idempotencyKey, request);
        PaymentResponse body = PaymentMapper.toPaymentResponse(result.payment());
        Response.Status status = result.created() ? Response.Status.CREATED : Response.Status.OK;
        return Response.status(status).entity(body).build();
    }

    @GET
    @Path("/history")
    public HistoryPageResponse history(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        return PaymentMapper.toHistoryResponse(historyService.list(payerId(), page, size));
    }

    @GET
    @Path("/{id}")
    public PaymentResponse get(@PathParam("id") String id) {
        return PaymentMapper.toPaymentResponse(getService.get(payerId(), parseId(id)));
    }

    @GET
    @Path("/{id}/receipt")
    public ReceiptResponse receipt(@PathParam("id") String id) {
        return PaymentMapper.toReceiptResponse(historyService.receipt(payerId(), parseId(id)));
    }

    /** SUPUESTO: el nombre del principal autenticado identifica al pagador (por ejemplo, subject del token). */
    private String payerId() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                || identity.getPrincipal().getName() == null || identity.getPrincipal().getName().isBlank()) {
            throw new UnauthorizedException();
        }
        return identity.getPrincipal().getName();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new PaymentValidationException("PAYMENT_ID_INVALID", "El identificador del pago no es válido.");
        }
    }
}
```

```java
// PaymentExceptionMapper.java
package pe.bcp.login.resource;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import pe.bcp.login.domain.GatewayUnavailableException;
import pe.bcp.login.domain.PaymentConflictException;
import pe.bcp.login.domain.PaymentException;
import pe.bcp.login.domain.PaymentNotFoundException;
import pe.bcp.login.domain.PaymentValidationException;
import pe.bcp.login.dto.ErrorResponse;

@Provider
public class PaymentExceptionMapper implements ExceptionMapper<PaymentException> {

    private static final Logger LOG = Logger.getLogger(PaymentExceptionMapper.class);

    @Override
    public Response toResponse(PaymentException e) {
        int status;
        if (e instanceof PaymentValidationException) {
            status = 400;
        } else if (e instanceof PaymentNotFoundException) {
            status = 404;
        } else if (e instanceof PaymentConflictException) {
            status = 409;
        } else if (e instanceof GatewayUnavailableException) {
            status = 503; // SUPUESTO: 503 no figura en la lista del encargo; confirmar con Arquitectura.
        } else {
            status = 500;
        }
        if (status >= 500) {
            LOG.errorf("Error de servidor controlado: %s", e.code());
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(e.code(), e.getMessage()))
                .build();
    }
}
```

```java
// ConstraintViolationExceptionMapper.java
package pe.bcp.login.resource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Comparator;
import java.util.List;
import pe.bcp.login.dto.ErrorResponse;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException e) {
        List<ErrorResponse.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v), v.getMessage()))
                .sorted(Comparator.comparing(ErrorResponse.FieldError::field)
                        .thenComparing(ErrorResponse.FieldError::message))
                .toList();
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("VALIDATION_ERROR", "La solicitud contiene datos inválidos.", fields))
                .build();
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String last = "request";
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null && !node.getName().isBlank()) {
                last = node.getName();
            }
        }
        return last;
    }
}
```

```java
// UnexpectedExceptionMapper.java
package pe.bcp.login.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import pe.bcp.login.dto.ErrorResponse;

/** Ultima red de seguridad: nunca devuelve trazas, SQL ni mensajes internos. */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        if (t instanceof WebApplicationException web) {
            return web.getResponse();
        }
        if (t instanceof JsonProcessingException) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("INVALID_JSON", "El cuerpo de la solicitud no es un JSON válido."))
                    .build();
        }
        LOG.error("Error no controlado", t);
        return Response.status(500)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("INTERNAL_ERROR",
                        "Ocurrió un error inesperado. Intenta nuevamente más tarde."))
                .build();
    }
}
```

---

## 3) Recorrido de un caso válido y uno de error

### Caso válido: pago exitoso (con `Idempotency-Key: abc12345-xyz`)

1. `POST /payments` llega a `PaymentResource.create`. Quarkus autentica con `@Authenticated`. Bean Validation valida `PaymentRequest` (formato, `amountMinor > 0`, moneda de 3 letras). Si falla, `ConstraintViolationExceptionMapper` responde 400.
2. `payerId()` toma el principal. Se delega a `CreatePaymentService.create`.
3. `validateKey` comprueba el header. Se calcula la huella SHA-256 del payload. `PaymentRepository.findByIdempotencyKey` no encuentra nada (SELECT parametrizado).
4. `PreviewPaymentService.preview`:
   - `PaymentLimitsConfigAdapter` devuelve los límites del proveedor/canal.
   - `AmountPolicy.validate` pasa.
   - `DestinationResolverPort` resuelve la identidad del destino.
5. `findPendingByPayer` no encuentra un pago en curso. Se construye `Payment.newPending` con expiración `ahora + pendingTtl`.
6. `PaymentRepository.insertIfAbsent` hace el INSERT parametrizado y devuelve `true`.
7. `announceCreated` publica el evento de funnel `PAYMENT_CREATED`, de forma segura.
8. `PaymentGatewayPort.initiate` devuelve, por ejemplo, `APPROVED` con una referencia. `PaymentResolutionService.apply` lo mapea a `SUCCEEDED` con `PaymentStatusMapper`. Luego `finish` ejecuta `UPDATE ... WHERE id = ? AND status = 'PENDING'` y devuelve `true`.
9. Como la transición fue ganada, se publica `publishActivation` (CA6) y el evento de funnel `PAYMENT_SUCCEEDED`.
10. El resource responde **201** con `PaymentResponse`: `status=SUCCEEDED`, `terminal=true` y `transactionReference`. El pago queda en `GET /payments/history` y `GET /payments/{id}/receipt` devuelve el comprobante.

Si el cliente repite la misma petición (doble clic o reintento de red), el paso 3 encuentra el intento con la misma huella y responde **200** con el mismo pago, sin cobrar de nuevo.

### Caso de error: monto fuera de rango

1. `POST /payments/preview` (o `POST /payments`) llega con un `amountMinor` mayor que el máximo configurado.
2. La validación de forma pasa. `PreviewPaymentService.preview` obtiene los límites y `AmountPolicy.validate` lanza `PaymentValidationException("AMOUNT_OUT_OF_RANGE", ...)`.
3. No se llega a la persistencia ni al proveedor. Si fue `POST /payments`, no hay INSERT ni cobro.
4. `PaymentExceptionMapper` responde **400** con `{"code":"AMOUNT_OUT_OF_RANGE","message":"El monto está fuera del rango permitido para este proveedor y canal."}`, sin trazas ni SQL.

### Error adicional: misma clave con otro payload

`CreatePaymentService.replay` compara huellas. Si difieren, lanza `PaymentConflictException("IDEMPOTENCY_KEY_REUSED")` y el mapper responde **409**.

---

## 4) Trazabilidad criterio de aceptación → código

| # | Criterio | Cobertura | Dónde |
|---|---|---|---|
| 1 | Antes de la confirmación final se muestra identidad del destino y monto | **Backend: cubierto.** Muestra y UI: pendiente de #17 | `PaymentResource.preview` → `PreviewPaymentService.preview` (límites, `AmountPolicy`, `DestinationResolverPort`) → `PreviewResponse`. Depende del adaptador `DestinationResolverPort`, **pendiente** |
| 2 | Un único resultado terminal por intento | **Cubierto** (lógica) | `PaymentStatus`, `PaymentStatusMapper`, `PaymentResolutionService.apply/refresh/finish`, `PaymentRepository.transitionIfPending` (compare-and-set), `ExpirePendingPaymentsService`, `PendingPaymentExpiryTrigger`. El mapeo usa valores crudos del mock **supuestos** (S6) |
| 3 | Éxito con referencia persistida en historial | **Cubierto** (lógica) | `transitionIfPending` guarda `transaction_reference`. `PaymentResolutionService.apply` exige referencia no vacía. `PaymentHistoryService.list` y `receipt`. Endpoints `history` y `receipt` |
| 4 | Fallo o expiración con siguiente acción, sin cobros duplicados | **Parcial.** Sin duplicados: cubierto. Mostrar la acción: pendiente de #17 | `CreatePaymentService` (`Idempotency-Key`, huella, 409 `IDEMPOTENCY_KEY_REUSED`, un solo `PENDING` por pagador → 409 `PAYMENT_IN_PROGRESS`, `insertIfAbsent` ante carreras). Reintentar crea un intento nuevo con clave nueva. `PaymentResponse` expone `status`, `terminal` y `failureReason` (código estable). No expone acciones, porque la UI las traduce (D8) |
| 5 | Caída en checkout de 30% a 8% | **NO cubierto por backend.** Es una métrica de negocio | Solo se habilitan los eventos de funnel (`PAYMENT_CREATED`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`) vía `PaymentEventPublisherPort`. Falta el adaptador, y la medición queda a cargo de Analytics/Producto |
| 6 | Transacciones exitosas emiten el evento de activación (MAU) | **Parcial** | `PaymentResolutionService.publishTerminalEvents` → `PaymentEventPublisherPort.publishActivation`, solo cuando la transición fue ganada (una vez por pago). Falta el **adaptador**. La publicación es best-effort: un fallo se registra y no se reintenta. Falta outbox (S8) |

Validaciones del encargo (#15):

- Forma de la entrada: `PaymentRequest` con Bean Validation.
- Header de idempotencia: `CreatePaymentService.validateKey`.
- Monto y límites: `AmountPolicy`.
- Límites por proveedor/canal: `PaymentsConfig` y `PaymentLimitsConfigAdapter`. La regla inicial sigue siendo pregunta abierta, por lo que no hay valores en el código.

---

## 5) Comandos de verificación (NO EJECUTADO)

No ejecuté ninguno de estos comandos. No afirmo que el código compile, funcione o sea seguro.

| Comando | Estado |
|---|---|
| `mvn -q clean compile` | **NO EJECUTADO** |
| `mvn test` (no hay tests escritos todavía; ver S10) | **NO EJECUTADO** |
| `mvn quarkus:dev` (falla hasta tener los adaptadores pendientes) | **NO EJECUTADO** |
| `curl -X POST http://localhost:8080/payments/preview -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"providerId":"<p>","channel":"<c>","destinationType":"<t>","destinationIdentifier":"<id>","amountMinor":<n>,"currency":"<ISO>"}'` | **NO EJECUTADO** |
| `curl -X POST http://localhost:8080/payments ...` (mismo body más `-H "Idempotency-Key: <clave>"`, repetido dos veces) | **NO EJECUTADO** |
| `curl http://localhost:8080/payments/<id>`, `/payments/history?page=0&size=20`, `/payments/<id>/receipt` | **NO EJECUTADO** |

Extensiones Quarkus necesarias (**NO verificado** contra el `pom.xml` real): `quarkus-rest-jackson`, `quarkus-hibernate-validator`, `quarkus-agroal` y el driver JDBC, `quarkus-scheduler`, y una extensión de seguridad (`quarkus-oidc` o `quarkus-smallrye-jwt`).

Estructura de configuración, sin secretos ni valores de negocio inventados:

```properties
# Credenciales y URL por variables de entorno (no en el código).
quarkus.datasource.jdbc.url=${PAYMENTS_DB_URL}
quarkus.datasource.username=${PAYMENTS_DB_USER}
quarkus.datasource.password=${PAYMENTS_DB_PASSWORD}

# Por proveedor y canal (valores definidos por Producto):
# payments.limits.<proveedor>.<canal>.currency=<ISO-4217>
# payments.limits.<proveedor>.<canal>.min-minor=<entero>
# payments.limits.<proveedor>.<canal>.max-minor=<entero>
# payments.pending-ttl=PT5M
# payments.expiry-check-interval=PT30S
```

---

## 6) Supuestos y pendientes para otros roles

### Supuestos (no confirmados)

- **S1. Paquetes.** Se aplicó `pe.bcp.login.service` a las clases de servicio. El resto usa subpaquetes (`domain`, `dto`, `port`, `adapter`, `config`, `repository`, `resource`, `scheduler`) para mantener las capas separadas. Si debe alinearse con la estructura real del BFF, hay que mover las clases.
- **S2. Contrato de API.** Rutas, códigos 200/201, formato de error `{code, message, fieldErrors}` y la presencia de `destinationType` son propuestas. El contrato de Arquitectura recibido estaba truncado.
- **S3. Modelo de Datos.** Tabla `payment` con las columnas listadas en `PaymentRepository.COLUMNS` (PostgreSQL, `uuid`, `timestamptz`). Se asume además:
  - unicidad `(payer_id, idempotency_key)`;
  - un solo `PENDING` por pagador;
  - índices por `(payer_id, status, completed_at)` y `(status, expires_at)`.

  Datos debe confirmar o ajustar. No se generó migración.
- **S4. Identidad del pagador.** Se asume que `SecurityIdentity.getPrincipal().getName()` es el identificador del pagador. No se aplican roles, así que no se emite 403 propio. Un pago ajeno responde 404.
- **S5. Historial.** Incluye pagos terminales (exitosos, fallidos y expirados). Si debe contener solo exitosos, cambiar el filtro en `PaymentRepository`.
- **S6. Estados del mock.** `SUCCESS/APPROVED`, `FAILED/REJECTED/DECLINED/ERROR`, `EXPIRED/TIMEOUT` y `PENDING/PROCESSING` son valores supuestos. Un valor desconocido queda `PENDING`.
- **S7. TTL de expiración.** Valor por defecto `PT5M`, supuesto. Producto/Arquitectura deben definirlo.

### Pendientes y riesgos

- **P1. Adaptadores faltantes (bloquea el arranque).**
  - `PaymentGatewayPort`: contrato del mock, timeouts y uso de `payment.id()` como referencia idempotente hacia el proveedor.
  - `DestinationResolverPort`: fuente de la identidad del destino.
  - `PaymentEventPublisherPort`: destino y formato del evento de activación MAU y de funnel.
- **P2. Outbox (Arquitectura/Datos).** Hoy la publicación de eventos es best-effort tras la transición. Si falla, el evento de activación (CA6) se pierde. Hace falta una tabla de outbox o un mecanismo equivalente.
- **P3. Expiración vs. cobro tardío (Arquitectura).**
  - Antes de expirar se consulta al proveedor.
  - Si el mock no permite cancelar, un pago aprobado después de `EXPIRED` quedaría cobrado y sin resultado exitoso. Se requiere reconciliación o cancelación.
  - Si el proveedor no responde de forma sostenida, el pago permanece `PENDING` más allá de su vencimiento. Decisión pendiente: política máxima de espera.
- **P4. Límites.** Producto debe definir la regla inicial de monto bajo por proveedor/canal. `PaymentLimitsConfigAdapter` no valida que `min <= max`.
- **P5. Códigos de estado (Arquitectura).** Confirmar el uso de 503 para proveedor no disponible (no está en la lista del encargo) y 201/200 en create.
- **P6. Frontend (#17).** Debe:
  - traducir los códigos `SUCCEEDED/FAILED/EXPIRED` a etiquetas (D8);
  - hacer polling a `GET /payments/{id}` hasta `terminal=true`;
  - deshabilitar el botón durante el envío;
  - generar una `Idempotency-Key` nueva por cada intento de reintento;
  - mostrar la siguiente acción (reintentar o volver).
- **P7. Analytics/Producto (CA5).** La medición de caída en checkout no es responsabilidad del backend.
- **P8. QA/Tests (S10).** No se escribieron tests. Hay que cubrir:
  - la concurrencia del compare-and-set;
  - la idempotencia (misma clave con mismo y distinto payload);
  - el rechazo de un segundo `PENDING`;
  - el mapeo de estados;
  - `AmountPolicy` en los bordes `min` y `max`.
- **P9. Límite del scheduler.** `findExpiredPending` procesa lotes de 100. Con volumen alto puede requerir ajuste.