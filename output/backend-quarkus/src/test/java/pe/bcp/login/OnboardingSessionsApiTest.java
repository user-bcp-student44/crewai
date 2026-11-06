// src/test/java/pe/bcp/login/resource/OnboardingSessionsApiTest.java
package pe.bcp.login.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static pe.bcp.login.support.OnboardingApiClient.newKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import pe.bcp.login.support.OnboardingApiClient;
import pe.bcp.login.support.OnboardingApiClient.Reply;
import pe.bcp.login.support.TestConfig;
import pe.bcp.login.support.TestDb;

class OnboardingSessionsApiTest {

    private static OnboardingApiClient api;

    @BeforeAll
    static void conectar() {
        assumeTrue(TestConfig.baseUrl() != null, "Falta -Donboarding.baseUrl");
        api = new OnboardingApiClient(TestConfig.baseUrl());
    }

    @BeforeEach
    void limpiarAntes() {
        limpiarUsuarios();
    }

    @AfterEach
    void limpiarDespues() {
        limpiarUsuarios();
    }

    private static void limpiarUsuarios() {
        if (!TestConfig.dbReady()) {
            return;
        }
        try (TestDb db = TestDb.open()) {
            db.deleteSessionsOf(TestConfig.userAId());
            db.deleteSessionsOf(TestConfig.userBId());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo limpiar la BD de pruebas", e);
        }
    }

    /** Pruebas que necesitan estado inicial limpio y tokens de ambos usuarios. */
    private static void requireFresh() {
        assumeTrue(TestConfig.dbReady(), "Falta configuración de BD de pruebas (onboarding.db.*)");
        assumeTrue(TestConfig.tokenA() != null && TestConfig.tokenB() != null,
                "Faltan onboarding.token.a / onboarding.token.b");
    }

    private static void assertNoLeak(Reply r) {
        String b = r.body() == null ? "" : r.body();
        for (String f : List.of("\tat ", "pe.bcp.", "org.postgresql", "SQLException",
                "SELECT ", "INSERT ", "org.hibernate", "java.lang.")) {
            assertFalse(b.contains(f), "Fuga de detalle interno: '" + f + "' en " + b);
        }
    }

    private static void assertModeloProgreso(JsonNode j) {
        UUID.fromString(j.get("sessionId").asText());
        assertTrue(j.get("steps").isArray() && j.get("steps").size() > 0, "steps vacío");
        assertEquals(j.get("steps").size(), j.get("totalSteps").asInt());
        assertTrue(j.get("version").isInt(), "version debe ser entero");
        assertTrue(Set.of("PENDING", "ACTIVE").contains(j.get("walletStatus").asText()));
        assertTrue(j.hasNonNull("nextAction") && j.get("nextAction").hasNonNull("type"));

        int previo = Integer.MIN_VALUE;
        int current = 0;
        String currentId = null;
        for (JsonNode s : j.get("steps")) {
            assertTrue(s.hasNonNull("stepId") && s.hasNonNull("label")
                    && s.has("required") && s.has("order"));
            assertTrue(s.get("order").asInt() > previo, "order debe ser creciente");
            previo = s.get("order").asInt();
            String st = s.get("status").asText();
            assertTrue(Set.of("COMPLETED", "CURRENT", "PENDING").contains(st), "status de paso: " + st);
            if ("CURRENT".equals(st)) {
                current++;
                currentId = s.get("stepId").asText();
            }
        }
        assertEquals(1, current, "Debe haber exactamente un paso CURRENT");
        assertEquals(currentId, j.get("currentStepId").asText());
    }

    // ---------------------------------------------------------------- positivos

    @Test
    @DisplayName("AP-01 crea sesión nueva: 201 y modelo de progreso")
    void ap01() {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(201, r.status(), r.body());
        assertTrue(r.contentType().contains("application/json"));
        JsonNode j = r.json();
        assertModeloProgreso(j);
        assertEquals("IN_PROGRESS", j.get("status").asText());
        assertEquals("PENDING", j.get("walletStatus").asText());
        assertEquals("CONTINUE_STEP", j.get("nextAction").get("type").asText());
        JsonNode steps = j.get("steps");
        assertEquals("CURRENT", steps.get(0).get("status").asText());
        for (int i = 1; i < steps.size(); i++) {
            assertEquals("PENDING", steps.get(i).get("status").asText());
        }
    }

    @Test
    @DisplayName("AP-02 reanuda con otra clave: 200 y mismo sessionId")
    void ap02() {
        requireFresh();
        Reply primera = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(201, primera.status(), primera.body());
        Reply segunda = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(200, segunda.status(), segunda.body());
        assertEquals(primera.json().get("sessionId").asText(), segunda.json().get("sessionId").asText());
        assertModeloProgreso(segunda.json());
    }

    @Test
    @DisplayName("AP-03 misma clave repetida: mismo sessionId (status 200 o 201)")
    void ap03() {
        requireFresh();
        String clave = newKey();
        Reply a = api.postSessions(TestConfig.tokenA(), clave, null, null);
        Reply b = api.postSessions(TestConfig.tokenA(), clave, null, null);
        assertTrue(Set.of(200, 201).contains(a.status()), a.body());
        assertTrue(Set.of(200, 201).contains(b.status()), b.body());
        assertEquals(a.json().get("sessionId").asText(), b.json().get("sessionId").asText());
    }

    @Test
    @Tag("catalogo-provisional")
    @DisplayName("AP-12 catálogo provisional de 3 pasos requeridos (SUPUESTO sin confirmar)")
    void ap12() {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, null);
        assertEquals(201, r.status(), r.body());
        JsonNode steps = r.json().get("steps");
        List<String> ids = new ArrayList<>();
        for (JsonNode s : steps) {
            ids.add(s.get("stepId").asText());
            assertTrue(s.get("required").asBoolean());
        }
        assertEquals(List.of("PERSONAL_DATA", "IDENTITY_DOCUMENT", "CONFIRMATION"), ids);
        assertEquals(3, r.json().get("totalSteps").asInt());
    }

    // -------------------------------------------------------------------- bordes

    @Test
    @DisplayName("AP-04 canal de 50 caracteres: 201")
    void ap04() {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null,
                "{\"acquisitionChannel\":\"" + "a".repeat(50) + "\"}");
        assertEquals(201, r.status(), r.body());
    }

    @Test
    @DisplayName("AP-14 body {} equivale a body vacío: 201")
    void ap14() {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, "{}");
        assertEquals(201, r.status(), r.body());
    }

    // ----------------------------------------------------------------- negativos

    @Test
    @DisplayName("AP-05 canal de 51 caracteres: 400 con fieldErrors y sin efectos")
    void ap05() throws Exception {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null,
                "{\"acquisitionChannel\":\"" + "a".repeat(51) + "\"}");
        assertEquals(400, r.status(), r.body());
        JsonNode j = r.json();
        assertTrue(j.hasNonNull("code") && !j.get("code").asText().isBlank());
        assertTrue(j.hasNonNull("message") && !j.get("message").asText().isBlank());
        assertTrue(j.hasNonNull("correlationId") && !j.get("correlationId").asText().isBlank());
        assertTrue(j.get("fieldErrors").isArray() && j.get("fieldErrors").size() >= 1);
        boolean campo = false;
        for (JsonNode fe : j.get("fieldErrors")) {
            campo |= fe.get("field").asText().contains("acquisitionChannel");
        }
        assertTrue(campo, "fieldErrors debe referir a acquisitionChannel: " + r.body());
        assertNoLeak(r);
        try (TestDb db = TestDb.open()) {
            assertEquals(0, db.count(TestConfig.userAId(), null));
        }
    }

    @Test
    @DisplayName("AP-06 sin Idempotency-Key: 400 y sin efectos")
    void ap06() throws Exception {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), null, null, null);
        assertEquals(400, r.status(), r.body());
        assertNoLeak(r);
        try (TestDb db = TestDb.open()) {
            assertEquals(0, db.count(TestConfig.userAId(), null));
        }
    }

    @ParameterizedTest(name = "AP-07 clave inválida [{0}]")
    @ValueSource(strings = {"no-es-uuid", "123", "00000000-0000-0000-0000-00000000000", "' OR 1=1 --"})
    void ap07(String claveInvalida) {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), claveInvalida, null, null);
        assertEquals(400, r.status(), r.body());
        assertNoLeak(r);
    }

    @Test
    @DisplayName("AP-08a sin Authorization: 401")
    void ap08a() {
        Reply r = api.postSessions(null, newKey(), null, null);
        assertEquals(401, r.status(), r.body());
        assertNoLeak(r);
    }

    @Test
    @DisplayName("AP-08b token inválido: 401")
    void ap08b() {
        Reply r = api.postSessions("token.invalido.xyz", newKey(), null, null);
        assertEquals(401, r.status(), r.body());
        assertNoLeak(r);
    }

    @Test
    @DisplayName("AP-13 JSON malformado: 400 y sin fuga")
    void ap13() {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null, "{\"acquisitionChannel\":");
        assertEquals(400, r.status(), r.body());
        assertNoLeak(r);
    }

    // ------------------------------------------------------------------ seguridad

    @Test
    @DisplayName("AP-09 el userId del body se ignora: la sesión pertenece al token")
    void ap09() throws Exception {
        requireFresh();
        Reply r = api.postSessions(TestConfig.tokenA(), newKey(), null,
                "{\"userId\":\"" + TestConfig.userBId() + "\"}");
        assertTrue(Set.of(200, 201, 400).contains(r.status()), r.body());
        if (r.status() != 400) {
            try (TestDb db = TestDb.open()) {
                assertEquals(1, db.count(TestConfig.userAId(), null));
                assertEquals(0, db.count(TestConfig.userBId(), null));
                Map<String, Object> fila = db.row(UUID.fromString(r.json().get("sessionId").asText()));
                assertEquals(TestConfig.userAId().toString(), String.valueOf(fila.get("user_id")));
            }
        }
    }

    @Test
    @DisplayName("AP-10 X-Correlation-Id: se genera, se conserva (UUID) y se sanea")
    void ap10() {
        requireFresh();
        // Se provoca un 400 estable (falta Idempotency-Key) para observar correlationId.
        Reply sin = api.postSessions(TestConfig.tokenA(), null, null, null);
        assertEquals(400, sin.status(), sin.body());
        assertFalse(sin.json().get("correlationId").asText().isBlank());

        String propio = UUID.randomUUID().toString();
        Reply con = api.postSessions(TestConfig.tokenA(), null, propio, null);
        assertEquals(400, con.status(), con.body());
        assertEquals(propio, con.json().get("correlationId").asText()); // inferencia: UUID se considera válido

        Reply hostil = api.postSessions(TestConfig.tokenA(), null, "<script>alert(1)</script>", null);
        assertEquals(400, hostil.status(), hostil.body());
        assertFalse(hostil.json().get("correlationId").asText().contains("<script>"));
    }

    // --------------------------------------------------------------- concurrencia

    @Test
    @DisplayName("AP-11 8 POST simultáneos del mismo usuario: una sola sesión creada")
    void ap11() throws Exception {
        requireFresh();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch listos = new CountDownLatch(n);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Reply>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    return api.postSessions(TestConfig.tokenB(), newKey(), null, null);
                }));
            }
            listos.await();
            salida.countDown();

            int creadas = 0;
            Set<String> ids = new HashSet<>();
            for (Future<Reply> f : futuros) {
                Reply r = f.get(30, TimeUnit.SECONDS);
                assertTrue(Set.of(200, 201).contains(r.status()), r.status() + " " + r.body());
                if (r.status() == 201) {
                    creadas++;
                }
                ids.add(r.json().get("sessionId").asText());
            }
            assertEquals(1, creadas, "Exactamente una respuesta 201");
            assertEquals(1, ids.size(), "Un único sessionId");
            try (TestDb db = TestDb.open()) {
                assertEquals(1, db.count(TestConfig.userBId(), null));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}