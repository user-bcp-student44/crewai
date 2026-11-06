// src/test/java/pe/bcp/login/api/BcpMockGroup1SmokeTest.java
package pe.bcp.login.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MOCK-01..03. SOLO LECTURA sobre el grupo 1. No llama a /control/reset (borra datos compartidos).
 * Se activa con -Dmock.smoke=true. La ruta /groups/1/control/events es un supuesto.
 */
class BcpMockGroup1SmokeTest {

    private static final String BASE = System.getProperty("mock.baseUrl",
            "https://bcp-wallet-mock.yefri.workers.dev").replaceAll("/+$", "");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void requiereActivacion() {
        assumeTrue(Boolean.getBoolean("mock.smoke"),
                "mock.smoke no es true: pruebas OMITIDAS (NO EJECUTADO)");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void assert2xxConCuerpo(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertTrue(r.statusCode() >= 200 && r.statusCode() < 300,
                path + " devolvio " + r.statusCode() + ": " + r.body());
        assertFalse(r.body() == null || r.body().isBlank(), path + " devolvio cuerpo vacio");
    }

    @Test
    void mock01_raizIndice() throws Exception {
        assert2xxConCuerpo("/");
    }

    @Test
    void mock02_grupo1ListaPagos() throws Exception {
        assert2xxConCuerpo("/groups/1/payments");
    }

    @Test
    void mock03_grupo1Eventos() throws Exception {
        assert2xxConCuerpo("/groups/1/control/events");
    }
}