// src/test/java/pe/bcp/login/support/TestDb.java
package pe.bcp.login.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso JDBC a la BD de PRUEBAS.
 * SUPUESTO (sin confirmar por Datos): tabla onboarding_session con columnas
 * id, user_id, status, current_step_index, version, wallet_status,
 * acquisition_channel, started_at, updated_at.
 */
public final class TestDb implements AutoCloseable {

    private final Connection conn;

    private TestDb(Connection conn) {
        this.conn = conn;
    }

    public static TestDb open() throws SQLException {
        return new TestDb(DriverManager.getConnection(
                TestConfig.get("onboarding.db.url"),
                TestConfig.get("onboarding.db.user"),
                TestConfig.get("onboarding.db.password")));
    }

    public void deleteSessionsOf(UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM onboarding_session WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }
    }

    /** status null = cualquier estado. */
    public int count(UUID userId, String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM onboarding_session WHERE user_id = ?"
                + (status == null ? "" : " AND status::text = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            if (status != null) {
                ps.setString(2, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public Map<String, Object> row(UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM onboarding_session WHERE id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (rs.next()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                    }
                }
                return m;
            }
        }
    }

    public void markCompleted(UUID sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE onboarding_session SET status = 'COMPLETED' WHERE id = ?")) {
            ps.setObject(1, sessionId);
            ps.executeUpdate();
        }
    }

    /** Inserta una copia IN_PROGRESS de una fila existente (mismo user_id) para forzar la restricción. */
    public void copyAsInProgress(UUID sourceId, UUID newId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO onboarding_session (id, user_id, status, current_step_index, version, "
                        + "wallet_status, acquisition_channel, started_at, updated_at) "
                        + "SELECT ?, user_id, 'IN_PROGRESS', current_step_index, version, "
                        + "wallet_status, acquisition_channel, started_at, updated_at "
                        + "FROM onboarding_session WHERE id = ?")) {
            ps.setObject(1, newId);
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}