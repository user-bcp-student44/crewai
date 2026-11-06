// src/test/java/pe/bcp/login/api/PaymentApiBlackBoxTest.java
package pe.bcp.login.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static pe.bcp.login.api.BffClient.DOMAIN_STATES;
import static pe.bcp.login.api.BffClient.PAYEE_VALUE;
import static pe.bcp.login.api.BffClient.assertErrorFormat;
import static pe.bcp.login.api.BffClient.assertNoLeak;
import static pe.bcp.login.api.BffClient.get;
import static pe.bcp.login.api.BffClient.paymentId;
import static pe.bcp.login.api.BffClient.payload;
import static pe.bcp.login.api.BffClient.post;
import static pe.bcp.login.api.BffClient.uniqueKey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pe.bcp.login.api.BffClient.Resp;

/**
 * Pruebas de caja negra del BFF (POST/GET /api/v1/payments).
 * Requiere -Dbff.baseUrl=http://host:puerto. Sin esa propiedad las pruebas se OMITEN (no son un pase).
 * Rutas, codigos y nombres de campo: ver supuestos H3-H6 del testing_report.md.
 */
class PaymentApiBlackBoxTest {

    @BeforeAll
    static void requiereConfiguracion() {
        assumeTrue(BffClient.configured(),
                "bff.baseUrl no definido: pruebas OMITIDAS (NO EJECUTADO)");
    }

    // API-01
    @Test
    void api01_crearPagoValido_devuelve201_ocultaCamposInternos() throws Exception {
        Resp r = post(uniqueKey("c01"), payload(1250));
        assertEquals(201, r.status(), "Cuerpo: " + r.body());
        paymentId(r);
        String status = r.json().path("status").asText();
        assertTrue(DOMAIN_STATES.contains(status), "Estado fuera del dominio: " + status);
        assertFalse(r.json().has("backendPaymentId"), "Expone backendPaymentId");
        assertFalse(r.json().has("payerWalletId"), "Expone payerWalletId");
        if (r.json().has("amountCents")) {
            assertEquals(1250L, r.json().get("amountCents").asLong());
        }
        if (r.json().has("currency")) {
            assertEquals("PEN", r.json().get("currency").asText());
        }
    }

    // API-02
    @Test
    void api02_replayMismaSolicitud_devuelve200_mismoPago() throws Exception {
        String key = uniqueKey("r02");
        String body = payload(1250);
        Resp first = post(key, body);
        assertEquals(201, first.status(), "Cuerpo: " + first.body());
        Resp second = post(key, body);
        assertEquals(200, second.status(), "Se esperaba 200 en el replay. Cuerpo: " + second.body());
        assertEquals(paymentId(first), paymentId(second));
        assertEquals(first.json().path("status").asText(), second.json().path("status").asText());
    }

    // API-03
    @Test
    void api03_mismaClaveOtroMonto_409KeyReused() throws Exception {
        String key = uniqueKey("m03");
        assertEquals(201, post(key, payload(1250)).status());
        Resp r = post(key, payload(1251));
        assertErrorFormat(r, 409, "IDEMPOTENCY_KEY_REUSED", false);
    }

    // API-04 (depende de que el hash incluya el destinatario; H10)
    @Test
    void api04_mismaClaveOtroDestinatario_409KeyReused() throws Exception {
        String key = uniqueKey("p04");
        assertEquals(201, post(key, payload(LongNode.valueOf(1250), "PEN", "merchant-demo")).status());
        Resp r = post(key, payload(LongNode.valueOf(1250), "PEN", "contact-demo"));
        assertErrorFormat(r, 409, "IDEMPOTENCY_KEY_REUSED", false);
    }

    // API-05
    @Test
    void api05_sinHeaderIdempotencyKey_400() throws Exception {
        Resp r = post(null, payload(1250));
        assertErrorFormat(r, 400, null, null);
    }

    static Stream<String> clavesInvalidas() {
        return Stream.of(
                "abc1234",                 // 7 caracteres
                "a".repeat(65),            // 65 caracteres
                "pay key 12345",           // espacio
                "pay.key.12345",           // punto
                "pay+key+12345",           // mas
                "pay/key/12345");          // barra
    }

    // API-06
    @ParameterizedTest(name = "clave invalida [{index}]")
    @MethodSource("clavesInvalidas")
    void api06_claveInvalida_400(String key) throws Exception {
        Resp r = post(key, payload(1250));
        assertErrorFormat(r, 400, null, null);
    }

    // API-07
    @Test
    void api07_clavesEnLosBordes8y64_201() throws Exception {
        String hex = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String k8 = "q" + hex.substring(0, 7);
        String k64 = "q" + hex.substring(0, 63);
        assertEquals(8, k8.length());
        assertEquals(64, k64.length());
        assertEquals(201, post(k8, payload(1250)).status());
        assertEquals(201, post(k64, payload(1250)).status());
    }

    static Stream<Arguments> montosInvalidos() throws IOException {
        return Stream.of(
                Arguments.of("cero", payload(LongNode.valueOf(0), "PEN", PAYEE_VALUE)),
                Arguments.of("negativo", payload(LongNode.valueOf(-1), "PEN", PAYEE_VALUE)),
                Arguments.of("decimal 12.5", payload(DoubleNode.valueOf(12.5), "PEN", PAYEE_VALUE)),
                Arguments.of("ausente", payload(null, "PEN", PAYEE_VALUE)));
    }

    // API-08
    @ParameterizedTest(name = "monto invalido: {0}")
    @MethodSource("montosInvalidos")
    void api08_montoInvalido_400(String caso, String body) throws Exception {
        Resp r = post(uniqueKey("a08"), body);
        assertErrorFormat(r, 400, null, null);
    }

    // API-09
    @Test
    void api09_montoMinimo1Centimo_201() throws Exception {
        Resp r = post(uniqueKey("b09"), payload(1));
        assertEquals(201, r.status(), "Cuerpo: " + r.body());
    }

    // API-10
    @Test
    void api10_monedaDistintaDePEN_400() throws Exception {
        Resp r = post(uniqueKey("m10"), payload(LongNode.valueOf(1250), "USD", PAYEE_VALUE));
        assertErrorFormat(r, 400, null, null);
    }

    // API-11
    @Test
    void api11_jsonMalformadoYCuerpoVacio_400() throws Exception {
        assertEquals(400, post(uniqueKey("j11"), "{").status());
        assertEquals(400, post(uniqueKey("v11"), "").status());
    }

    // API-12
    @Test
    void api12_consultarPagoExistente_200() throws Exception {
        Resp created = post(uniqueKey("g12"), payload(1250));
        assertEquals(201, created.status(), "Cuerpo: " + created.body());
        String id = paymentId(created);
        Resp got = get("/api/v1/payments/" + id);
        assertEquals(200, got.status(), "Cuerpo: " + got.body());
        assertEquals(id, paymentId(got));
        assertTrue(DOMAIN_STATES.contains(got.json().path("status").asText()));
        assertFalse(got.json().has("backendPaymentId"));
        assertFalse(got.json().has("payerWalletId"));
    }

    // API-13
    @Test
    void api13_consultarPagoInexistente_404() throws Exception {
        Resp r = get("/api/v1/payments/" + UUID.randomUUID());
        assertErrorFormat(r, 404, null, null);
    }

    // API-14 (supone que el id es un UUID; H6)
    @Test
    void api14_idMalFormado_400() throws Exception {
        Resp r = get("/api/v1/payments/no-es-un-uuid");
        assertErrorFormat(r, 400, null, null);
    }

    // API-15
    @Test
    void api15_concurrenciaMismaClave_unSoloCargo() throws Exception {
        final int n = 10;
        final String key = uniqueKey("k15");
        final String body = payload(1250);
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return post(key, body);
                }));
            }
            ready.await();
            go.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            long created = results.stream().filter(r -> r.status() == 201).count();
            assertEquals(1, created, "Debe haber exactamente un 201");
            Set<String> ids = new HashSet<>();
            for (Resp r : results) {
                if (r.status() == 201 || r.status() == 200) {
                    ids.add(paymentId(r));
                } else {
                    assertErrorFormat(r, 409, "IDEMPOTENCY_IN_PROGRESS", true);
                }
            }
            assertEquals(1, ids.size(), "Mas de un paymentId para la misma clave: " + ids);
            Resp got = get("/api/v1/payments/" + ids.iterator().next());
            assertEquals(200, got.status());
        } finally {
            ex.shutdownNow();
        }
    }

    // API-16
    @Test
    void api16_inyeccionSql_noFiltraInformacion() throws Exception {
        Resp badKey = post("x'; DROP TABLE t;--", payload(1250));
        assertErrorFormat(badKey, 400, null, null);

        String body = payload(LongNode.valueOf(1250), "PEN", "'; DROP TABLE x;--");
        Resp badPayee = post(uniqueKey("s16"), body);
        assertTrue(badPayee.status() < 500, "Error de servidor: " + badPayee.body());
        assertNoLeak(badPayee);
    }
}