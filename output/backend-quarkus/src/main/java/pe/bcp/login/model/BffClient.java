// src/test/java/pe/bcp/login/api/BffClient.java
package pe.bcp.login.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Cliente HTTP minimo para pruebas de caja negra del BFF. No depende de clases de produccion. */
final class BffClient {

    static final String BASE = System.getProperty("bff.baseUrl", "").trim();
    static final String PAYEE_FIELD = System.getProperty("bff.payeeField", "payeeId");
    static final String PAYEE_VALUE = System.getProperty("bff.payeeValue", "merchant-demo");
    static final String EXTRA_JSON = System.getProperty("bff.extraJson", "").trim();

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    static final Set<String> DOMAIN_STATES =
            Set.of("PENDING", "CONFIRMED", "FAILED", "EXPIRED", "REVERSED");
    static final List<String> LEAK_MARKERS = List.of(
            "SQLException", "org.postgresql", "java.lang.", "at pe.bcp",
            "SELECT ", "INSERT INTO", "ON CONFLICT", "stackTrace");

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private BffClient() {}

    record Resp(int status, String body, JsonNode json) {}

    static boolean configured() {
        return !BASE.isEmpty();
    }

    /** Clave valida segun ^[A-Za-z0-9_-]{8,64}$ y unica por ejecucion. */
    static String uniqueKey(String tag) {
        return "qa-" + RUN_ID + "-" + tag + "-" + COUNTER.incrementAndGet();
    }

    static String payload(JsonNode amount, String currency, String payee) throws IOException {
        ObjectNode n = MAPPER.createObjectNode();
        if (amount != null) {
            n.set("amountCents", amount);
        }
        if (currency != null) {
            n.put("currency", currency);
        }
        n.put(PAYEE_FIELD, payee);
        if (!EXTRA_JSON.isEmpty()) {
            n.setAll((ObjectNode) MAPPER.readTree(EXTRA_JSON));
        }
        return MAPPER.writeValueAsString(n);
    }

    static String payload(long cents) throws IOException {
        return payload(LongNode.valueOf(cents), "PEN", PAYEE_VALUE);
    }

    static Resp post(String idemKey, String rawBody) throws IOException, InterruptedException {
        return send("POST", "/api/v1/payments", idemKey, rawBody);
    }

    static Resp get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null);
    }

    static Resp send(String method, String path, String idemKey, String rawBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (rawBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, BodyPublishers.ofString(rawBody));
        } else {
            b.method(method, BodyPublishers.noBody());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = null;
        String body = r.body() == null ? "" : r.body();
        if (!body.isBlank()) {
            try {
                json = MAPPER.readTree(body);
            } catch (IOException ignored) {
                json = null;
            }
        }
        return new Resp(r.statusCode(), body, json);
    }

    static String paymentId(Resp r) {
        JsonNode n = r.json() == null ? null : r.json().get("paymentId");
        assertTrue(n != null && n.isTextual() && !n.asText().isBlank(),
                "La respuesta no trae 'paymentId' (nombre inferido de la ruta; ver H5). Cuerpo: " + r.body());
        return n.asText();
    }

    static void assertNoLeak(Resp r) {
        for (String m : LEAK_MARKERS) {
            assertFalse(r.body().contains(m), "La respuesta filtra '" + m + "': " + r.body());
        }
    }

    /** Verifica el formato unico de error. expectedCode/expectedRetryable pueden ser null. */
    static void assertErrorFormat(Resp r, int expectedStatus, String expectedCode,
                                  Boolean expectedRetryable) {
        assertEquals(expectedStatus, r.status(), "Status inesperado. Cuerpo: " + r.body());
        JsonNode j = r.json();
        assertTrue(j != null && j.isObject(), "El error no es un objeto JSON: " + r.body());
        assertTrue(j.hasNonNull("code") && j.get("code").isTextual(), "Falta 'code'");
        assertTrue(j.hasNonNull("message") && j.get("message").isTextual(), "Falta 'message'");
        assertTrue(j.hasNonNull("retryable") && j.get("retryable").isBoolean(),
                "Falta 'retryable' booleano");
        assertTrue(j.hasNonNull("traceId") && !j.get("traceId").asText().isBlank(),
                "Falta 'traceId'");
        if (expectedCode != null) {
            assertEquals(expectedCode, j.get("code").asText());
        }
        if (expectedRetryable != null) {
            assertEquals(expectedRetryable, j.get("retryable").asBoolean());
        }
        assertNoLeak(r);
    }
}