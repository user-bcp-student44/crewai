// src/test/java/pe/bcp/login/repository/OnboardingSessionPersistenceTest.java
package pe.bcp.login.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static pe.bcp.login.support.OnboardingApiClient.newKey;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pe.bcp.login.support.OnboardingApiClient;
import pe.bcp.login.support.OnboardingApiClient.Reply;
import pe.bcp.login.support.TestConfig;
import pe.bcp.login.support.TestDb;

/**
 * Verifica persistencia contra el ESQUEMA SUPUESTO por Backend (no confirmado por Datos):
 * tabla onboarding_session e índice único parcial (user_id) WHERE status='IN_PROGRESS'.
 */
class OnboardingSessionPersistenceTest {

    private static OnboardingApiClient api;

    @BeforeAll
    static void preparar() {
        assumeTrue(TestConfig.baseUrl() != null, "Falta -Donboarding.baseUrl");
        assumeTrue(TestConfig.dbReady(), "Falta configuración de BD de pruebas (onboarding.db.*)");
        assumeTrue(TestConfig.tokenA() != null && TestConfig.tokenB() != null,
                "Faltan tokens de A y B");
        api = new OnboardingApiClient(TestConfig.baseUrl());
    }

    @BeforeEach
    @AfterEach
    void limpiar() {
        // JUnit 5 permite anotar el mismo método con ambos ciclos de vida.
        try (TestDb db = TestDb.open()) {
            db.deleteSessionsOf(TestConfig.userAId());
            db.deleteSessionsOf(TestConfig.userBId());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo limpiar la BD de pruebas", e);
        }
    }

    private static UUID crearSesionA(String bodyJson) {
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, bodyJson);
        assertEquals(201, r.status(), r.body());
        return UUID.fromString(r.json().get("sessionId").asText());
    }

    @Test
    @DisplayName("PE-01 la fila creada refleja usuario, estado, wallet y canal")
    void pe01() throws Exception {
        UUID id = crearSesionA("{\"acquisitionChannel\":\"web-test\"}");
        try (TestDb db = TestDb.open()) {
            Map<String, Object> f = db.row(id);
            assertEquals(id.toString(), String.valueOf(f.get("id")));
            assertEquals(TestConfig.userAId().toString(), String.valueOf(f.get("user_id")));
            assertEquals("IN_PROGRESS", String.valueOf(f.get("status")));
            assertEquals("PENDING", String.valueOf(f.get("wallet_status")));
            assertEquals("web-test", String.valueOf(f.get("acquisition_channel")));
            assertNotNull(f.get("started_at"));
            assertNotNull(f.get("updated_at"));
        }
    }

    @Test
    @DisplayName("PE-02 reanudar no crea filas adicionales")
    void pe02() throws Exception {
        crearSesionA(null);
        Reply segunda = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(200, segunda.status(), segunda.body());
        try (TestDb db = TestDb.open()) {
            assertEquals(1, db.count(TestConfig.userAId(), null));
        }
    }

    @Test
    @DisplayName("PE-03 el índice único parcial rechaza una segunda IN_PROGRESS (23505)")
    void pe03() throws Exception {
        UUID id = crearSesionA(null);
        try (TestDb db = TestDb.open()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> db.copyAsInProgress(id, UUID.randomUUID()));
            assertEquals("23505", ex.getSQLState());
            assertEquals(1, db.count(TestConfig.userAId(), "IN_PROGRESS"));
        }
    }

    @Test
    @DisplayName("PE-04 tras COMPLETED se permite una nueva IN_PROGRESS")
    void pe04() throws Exception {
        UUID id = crearSesionA(null);
        try (TestDb db = TestDb.open()) {
            db.markCompleted(id);
            db.copyAsInProgress(id, UUID.randomUUID());
            assertEquals(2, db.count(TestConfig.userAId(), null));
            assertEquals(1, db.count(TestConfig.userAId(), "IN_PROGRESS"));
        }
    }

    @Test
    @DisplayName("PE-05 cada usuario tiene su propia IN_PROGRESS")
    void pe05() throws Exception {
        UUID a = crearSesionA(null);
        Reply rb = api.postSessions(TestConfig.tokenB(), newKey(), null, null);
        assertEquals(201, rb.status(), rb.body());
        UUID b = UUID.fromString(rb.json().get("sessionId").asText());
        assertNotEquals(a, b);
        try (TestDb db = TestDb.open()) {
            assertEquals(1, db.count(TestConfig.userAId(), "IN_PROGRESS"));
            assertEquals(1, db.count(TestConfig.userBId(), "IN_PROGRESS"));
        }
    }

    @Test
    @DisplayName("PE-06 tras COMPLETED, E1 crea una sesión nueva (201)")
    void pe06() throws Exception {
        UUID primera = crearSesionA(null);
        try (TestDb db = TestDb.open()) {
            db.markCompleted(primera);
        }
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(201, r.status(), r.body());
        assertNotEquals(primera.toString(), r.json().get("sessionId").asText());
    }
}