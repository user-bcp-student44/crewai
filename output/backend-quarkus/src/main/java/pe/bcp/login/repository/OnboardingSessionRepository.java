// OnboardingSessionRepository.java
package pe.bcp.login.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.bcp.login.exception.DuplicateSessionException;
import pe.bcp.login.exception.StorageException;
import pe.bcp.login.model.OnboardingSession;
import pe.bcp.login.model.OnboardingStatus;
import pe.bcp.login.model.WalletStatus;

/**
 * Acceso a datos con JDBC y consultas parametrizadas.
 * SUPUESTO: tabla onboarding_session (PostgreSQL) pendiente de confirmar con Datos.
 * Columnas: id, user_id, status, current_step_index, version, wallet_status,
 * acquisition_channel, started_at, updated_at.
 * Se espera un índice único parcial (user_id) WHERE status = 'IN_PROGRESS'.
 */
@ApplicationScoped
public class OnboardingSessionRepository {

    private static final Logger LOG = Logger.getLogger(OnboardingSessionRepository.class);
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String SELECT_IN_PROGRESS =
            "SELECT id, user_id, status, current_step_index, version, wallet_status, "
                    + "acquisition_channel, started_at, updated_at "
                    + "FROM onboarding_session WHERE user_id = ? AND status = ?";

    private static final String INSERT =
            "INSERT INTO onboarding_session (id, user_id, status, current_step_index, version, "
                    + "wallet_status, acquisition_channel, started_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Inject
    DataSource dataSource;

    public Optional<OnboardingSession> findInProgressByUserId(UUID userId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_IN_PROGRESS)) {
            statement.setObject(1, userId);
            statement.setString(2, OnboardingStatus.IN_PROGRESS.name());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            LOG.error("Fallo al consultar la sesión de onboarding en curso", e);
            throw new StorageException("Error de persistencia", e);
        }
    }

    public void insert(OnboardingSession session) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, session.id());
            statement.setObject(2, session.userId());
            statement.setString(3, session.status().name());
            statement.setInt(4, session.currentStepIndex());
            statement.setInt(5, session.version());
            statement.setString(6, session.walletStatus().name());
            statement.setString(7, session.acquisitionChannel());
            statement.setObject(8, toOffset(session.startedAt()));
            statement.setObject(9, toOffset(session.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new DuplicateSessionException("Ya existe una sesión en curso para el usuario");
            }
            LOG.error("Fallo al insertar la sesión de onboarding", e);
            throw new StorageException("Error de persistencia", e);
        }
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OnboardingSession map(ResultSet rs) throws SQLException {
        return new OnboardingSession(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                OnboardingStatus.valueOf(rs.getString("status")),
                rs.getInt("current_step_index"),
                rs.getInt("version"),
                WalletStatus.valueOf(rs.getString("wallet_status")),
                rs.getString("acquisition_channel"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}