// src/test/java/pe/bcp/login/api/IdempotencyKeyFormatContractTest.java
package pe.bcp.login.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * CT-01. Contrato de formato de clave entre cliente (pay- + 32 hex) y servidor
 * (^[A-Za-z0-9_-]{8,64}$). El patron esta COPIADO de los specs de Backend/Frontend, no importado
 * del codigo de produccion: si el patron real cambia, esta prueba no lo detecta.
 */
class IdempotencyKeyFormatContractTest {

    private static final Pattern SERVER = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Test
    void claveDelCliente_cumpleElPatronDelServidor() {
        for (int i = 0; i < 100; i++) {
            String key = "pay-" + UUID.randomUUID().toString().replace("-", "");
            assertEquals(36, key.length());
            assertTrue(SERVER.matcher(key).matches(), key);
        }
    }

    @Test
    void bordesDelPatron() {
        assertTrue(SERVER.matcher("a".repeat(8)).matches());
        assertTrue(SERVER.matcher("a".repeat(64)).matches());
        assertFalse(SERVER.matcher("a".repeat(7)).matches());
        assertFalse(SERVER.matcher("a".repeat(65)).matches());
        assertFalse(SERVER.matcher("pay key 1234").matches());
        assertFalse(SERVER.matcher("pay.key.1234").matches());
    }
}