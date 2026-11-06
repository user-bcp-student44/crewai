// src/test/java/pe/bcp/login/support/OnboardingApiClient.java
package pe.bcp.login.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Cliente HTTP de caja negra para E1. Solo usa el JDK y Jackson. */
public final class OnboardingApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Reply(int status, String body, String contentType) {
        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("El cuerpo no es JSON: " + body, e);
            }
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public OnboardingApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Cualquier parámetro null omite la cabecera o el body correspondiente. */
    public Reply postSessions(String bearerToken, String idempotencyKey,
                              String correlationId, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/onboarding/sessions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (bearerToken != null) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            if (correlationId != null) {
                b.header("X-Correlation-Id", correlationId);
            }
            b.POST(jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body(),
                    r.headers().firstValue("content-type").orElse(""));
        } catch (IOException e) {
            throw new IllegalStateException("Fallo de red al llamar a E1", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Llamada interrumpida", e);
        }
    }
}