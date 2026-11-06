// src/test/java/pe/bcp/login/exception/OnboardingExceptionsTest.java
package pe.bcp.login.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OnboardingExceptionsTest {

    @Test
    @DisplayName("UT-05 OnboardingValidationException copia defensivamente y conserva el orden")
    void ut05() {
        Map<String, String> origen = new LinkedHashMap<>();
        origen.put("b", "2");
        origen.put("a", "1");

        OnboardingValidationException ex = new OnboardingValidationException("msg", origen);
        origen.put("c", "3");

        assertEquals("msg", ex.getMessage());
        assertEquals(List.of("b", "a"), List.copyOf(ex.getFieldErrors().keySet()));
        assertEquals("2", ex.getFieldErrors().get("b"));
    }

    @Test
    @DisplayName("UT-06 StorageException conserva la causa")
    void ut06() {
        RuntimeException causa = new RuntimeException("x");
        StorageException ex = new StorageException("m", causa);
        assertEquals("m", ex.getMessage());
        assertSame(causa, ex.getCause());
    }

    @Test
    @DisplayName("UT-07 DuplicateSessionException conserva el mensaje")
    void ut07() {
        DuplicateSessionException ex = new DuplicateSessionException("dup");
        assertEquals("dup", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }
}