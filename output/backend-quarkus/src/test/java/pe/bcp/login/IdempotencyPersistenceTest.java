// src/test/java/pe/bcp/login/api/IdempotencyPersistenceTest.java
package pe.bcp.login.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * DB-01 y DB-02. Solo usan nombres conocidos: la columna idempotency_key (por el ON CONFLICT del spec)
 * y columnas '%amount%'. Requiere -Dbff.db.url (+ -Dbff.db.user, -Dbff.db.password). Solo lectura.
 */
class IdempotencyPersistenceTest {

    private static final String URL = System.getProperty("bff.db.url", "").trim();
    private static final String USER = System.getProperty("bff.db.user", "");
    private static final String PASS = System.getProperty("bff.db.password", "");

    @BeforeAll
    static void requiereConfiguracion() {
        assumeTrue(!URL.isEmpty(), "bff.db.url no definido: pruebas OMITIDAS (NO EJECUTADO)");
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    // DB-01
    @Test
    void db01_existeIndiceUnicoExactoSobreIdempotencyKey() throws Exception {
        String sql = "SELECT tablename, indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname = current_schema() "
                + "AND indexdef ILIKE 'CREATE UNIQUE INDEX%' "
                + "AND indexdef ~* '\\(idempotency_key\\)'";
        List<String> found = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                found.add(rs.getString("tablename") + "." + rs.getString("indexname"));
            }
        }
        assertFalse(found.isEmpty(),
                "No hay indice unico exacto sobre (idempotency_key): ON CONFLICT no funcionaria");
    }

    // DB-02
    @Test
    void db02_importesNoUsanTiposDecimalesNiFlotantes() throws Exception {
        String sql = "SELECT table_name, column_name, data_type, numeric_scale "
                + "FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND column_name ILIKE '%amount%'";
        int columnas = 0;
        List<String> malas = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                columnas++;
                String tipo = rs.getString("data_type");
                int escala = rs.getInt("numeric_scale");
                boolean mal = tipo.equals("real") || tipo.equals("double precision")
                        || tipo.equals("money") || (tipo.equals("numeric") && escala > 0);
                if (mal) {
                    malas.add(rs.getString("table_name") + "." + rs.getString("column_name")
                            + " (" + tipo + ")");
                }
            }
        }
        assertTrue(columnas > 0, "No se encontro ninguna columna '%amount%' (nombre real pendiente de Datos)");
        assertTrue(malas.isEmpty(), "Importes con tipo no entero: " + malas);
    }
}