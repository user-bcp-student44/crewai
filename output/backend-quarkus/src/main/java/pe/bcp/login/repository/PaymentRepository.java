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