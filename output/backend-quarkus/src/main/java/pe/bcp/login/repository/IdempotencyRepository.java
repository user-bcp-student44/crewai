// IdempotencyRepository.java
package pe.bcp.login.repository;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import pe.bcp.login.model.IdempotencyRecord;
import pe.bcp.login.model.IdempotencyStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Optional;

/**
 * Acceso a datos de idempotencia. Todas las consultas son parametrizadas.
 * Los nombres de tabla y columna son un SUPUESTO pendiente de confirmar con Datos.
 */
@ApplicationScoped
public class IdempotencyRepository {

    private static final String INSERT_SQL =
            "INSERT INTO idempotency_record "
                    + "(idempotency_key, request_hash, status, payment_id, created_at) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (idempotency_key) DO NOTHING";

    private static final String SELECT_SQL =
            "SELECT idempotency_key, request_hash, status, payment_id, created_at "
                    + "FROM idempotency_record WHERE idempotency_key = ?";

    private static final String COMPLETE_SQL =
            "UPDATE idempotency_record SET status = ? WHERE idempotency_key = ?";

    private final AgroalDataSource dataSource;

    @Inject
    public IdempotencyRepository(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Inserta la clave en estado PROCESSING.
     *
     * @return true si se insertó; false si la clave ya existía.
     */
    @Transactional
    public boolean tryInsert(String idempotencyKey, String requestHash, String paymentId, Instant createdAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, idempotencyKey);
            statement.setString(2, requestHash);
            statement.setString(3, IdempotencyStatus.PROCESSING.name());
            statement.setString(4, paymentId);
            statement.setObject(5, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo registrar la clave de idempotencia", e);
        }
    }

    @Transactional
    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                return Optional.of(new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        IdempotencyStatus.valueOf(rs.getString("status")),
                        rs.getString("payment_id"),
                        createdAt == null ? null : createdAt.toInstant()));
            }
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo consultar la clave de idempotencia", e);
        }
    }

    @Transactional
    public void markCompleted(String idempotencyKey) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COMPLETE_SQL)) {
            statement.setString(1, IdempotencyStatus.COMPLETED.name());
            statement.setString(2, idempotencyKey);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RepositoryException("No se pudo completar la clave de idempotencia", e);
        }
    }
}