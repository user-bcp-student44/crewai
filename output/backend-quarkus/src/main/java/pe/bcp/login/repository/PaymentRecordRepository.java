// PaymentRecordRepository.java
package pe.bcp.login.repository;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import pe.bcp.login.model.PaymentRecord;
import pe.bcp.login.model.PaymentStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Acceso a datos de pagos del BFF. Consultas parametrizadas.
 * Los nombres de tabla y columna son un SUPUESTO pendiente de confirmar con Datos.
 */
@ApplicationScoped
public class PaymentRecordRepository {

    private static final String INSERT_SQL =
            "INSERT INTO payment_record "
                    + "(payment_id, backend_payment_id, payer_wallet_id, destination, amount_cents, "
                    + "currency, description, status, failure_code, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_OUTCOME_SQL =
            "UPDATE payment_record SET status = ?, backend_payment_id = ?, failure_code = ?, updated_at = ? "
                    + "WHERE payment_id = ?";

    private static final String SELECT_SQL =
            "SELECT payment_id, backend_payment_id, payer_wallet_id, destination, amount_cents, "
                    + "currency, description, status, failure_code, created_at, updated_at "
                    + "FROM payment_record WHERE payment_id = ?";

    private final AgroalDataSource dataSource;

    @Inject
    public PaymentRecordRepository(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Transactional
    public void insert(PaymentRecord payment) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, payment.paymentId());
            setNullableString(statement, 2, payment.backendPaymentId());
            statement.setString(3, payment.payerWalletId());
            statement.setString(4, payment.destination());
            statement.setLong(5, payment.amountCents());
            statement.setString(6, payment.currency());
            setNullableString(statement, 7, payment.description());
            statement.setString(8, payment.status().name());
            setNullableString(statement, 9, payment.failureCode());
            statement.setObject(10, OffsetDateTime.ofInstant(payment.createdAt(), ZoneOffset.UTC));
            statement.setObject(11, OffsetDateTime.ofInstant(payment.updatedAt(), ZoneOffset.UTC));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo guardar el pago", e);
        }
    }

    @Transactional
    public void updateOutcome(String paymentId,
                              PaymentStatus status,
                              String backendPaymentId,
                              String failureCode,
                              Instant updatedAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_OUTCOME_SQL)) {
            statement.setString(1, status.name());
            setNullableString(statement, 2, backendPaymentId);
            setNullableString(statement, 3, failureCode);
            statement.setObject(4, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
            statement.setString(5, paymentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo actualizar el pago", e);
        }
    }

    @Transactional
    public Optional<PaymentRecord> findById(String paymentId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, paymentId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                return Optional.of(new PaymentRecord(
                        rs.getString("payment_id"),
                        rs.getString("backend_payment_id"),
                        rs.getString("payer_wallet_id"),
                        rs.getString("destination"),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("description"),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getString("failure_code"),
                        createdAt == null ? null : createdAt.toInstant(),
                        updatedAt == null ? null : updatedAt.toInstant()));
            }
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo consultar el pago", e);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}