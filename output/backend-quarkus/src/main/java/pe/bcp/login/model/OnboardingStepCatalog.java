// OnboardingStepCatalog.java
package pe.bcp.login.service;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import pe.bcp.login.model.StepDefinition;

/**
 * Catálogo PROVISIONAL de pasos. Es un supuesto del contrato, pendiente de Cumplimiento y Producto.
 * Debe migrarse a configuración externa cuando los requisitos de KYC estén definidos.
 */
@ApplicationScoped
public class OnboardingStepCatalog {

    private static final List<StepDefinition> STEPS = List.of(
            new StepDefinition("PERSONAL_DATA", 1, "Datos personales", true),
            new StepDefinition("IDENTITY_DOCUMENT", 2, "Documento de identidad", true),
            new StepDefinition("CONFIRMATION", 3, "Confirmación", true));

    public List<StepDefinition> steps() {
        return STEPS;
    }

    public int totalSteps() {
        return STEPS.size();
    }

    public Optional<StepDefinition> byOrder(int order) {
        return STEPS.stream().filter(s -> s.order() == order).findFirst();
    }
}