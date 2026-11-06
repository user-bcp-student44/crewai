// OnboardingProgressResponse.java
package pe.bcp.login.dto;

import java.util.List;
import java.util.UUID;

public record OnboardingProgressResponse(
        UUID sessionId,
        String status,
        int totalSteps,
        int currentStepIndex,
        String currentStepId,
        List<StepView> steps,
        int version,
        String walletStatus,
        NextAction nextAction) {

    public record StepView(String stepId, int order, String label, boolean required, String status) {
    }

    public record NextAction(String type, String target) {
    }
}