// src/test/java/pe/bcp/login/dto/StartOnboardingRequestValidationTest.java
package pe.bcp.login.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class StartOnboardingRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static Set<ConstraintViolation<StartOnboardingRequest>> validar(String canal) {
        StartOnboardingRequest r = new StartOnboardingRequest();
        r.acquisitionChannel = canal;
        return validator.validate(r);
    }

    @Test
    @DisplayName("UT-01 canal null es válido (opcional)")
    void ut01() {
        assertTrue(validar(null).isEmpty());
    }

    @Test
    @DisplayName("UT-02 canal de 50 caracteres es válido (borde superior)")
    void ut02() {
        assertTrue(validar("a".repeat(50)).isEmpty());
    }

    @Test
    @DisplayName("UT-03 canal de 51 caracteres es inválido con mensaje accionable")
    void ut03() {
        Set<ConstraintViolation<StartOnboardingRequest>> v = validar("a".repeat(51));
        assertEquals(1, v.size());
        ConstraintViolation<StartOnboardingRequest> c = v.iterator().next();
        assertEquals("acquisitionChannel", c.getPropertyPath().toString());
        assertEquals("El canal de adquisición no puede superar 50 caracteres.", c.getMessage());
    }

    @Test
    @DisplayName("UT-04 canal vacío es válido para la anotación @Size")
    void ut04() {
        assertTrue(validar("").isEmpty());
    }
}