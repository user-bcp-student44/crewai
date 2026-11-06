// OnboardingProgressAssembler.java
package pe.bcp.login.service;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import pe.bcp.login.dto.OnboardingProgressResponse;
import pe.bcp.login.dto.OnboardingProgressResponse.NextAction;
import pe.bcp.login.dto.OnboardingProgressResponse.StepView;
import pe.bcp.login.model.OnboardingSession;
import pe.bcp.login.model.OnboardingStatus;
import pe.bcp.login.model.StepDefinition;

@ApplicationScoped
public class OnboardingProgressAssembler {

    private static final String TARGET_LOW_AMOUNT_PAYMENTS = "LOW_AMOUNT_PAYMENTS_ENTRY";

    @Inject
    OnboardingStepCatalog catalog;

    public OnboardingProgressResponse toResponse(OnboardingSession session) {
        List<StepView> views = new ArrayList<>();
        for (StepDefinition definition : catalog.steps()) {
            views.add(new StepView(
                    definition.stepId(),
                    definition.order(),
                    definition.label(),
                    definition.required(),
                    stepStatus(session, definition)));
        }

        String currentStepId = null;
        NextAction nextAction = null;
        if (session.status() == OnboardingStatus.IN_PROGRESS) {
            currentStepId = catalog.byOrder(session.currentStepIndex())
                    .map(StepDefinition::stepId)
                    .orElse(null);
            nextAction = new NextAction("CONTINUE_STEP", currentStepId);
        } else if (session.status() == OnboardingStatus.COMPLETED) {
            nextAction = new NextAction("GO_TO_LOW_AMOUNT_PAYMENTS", TARGET_LOW_AMOUNT_PAYMENTS);
        }

        return new OnboardingProgressResponse(
                session.id(),
                session.status().name(),
                catalog.totalSteps(),
                session.currentStepIndex(),
                currentStepId,
                views,
                session.version(),
                session.walletStatus().name(),
                nextAction);
    }

    private static String stepStatus(OnboardingSession session, StepDefinition definition) {
        if (session.status() == OnboardingStatus.COMPLETED
                || definition.order() < session.currentStepIndex()) {
            return "COMPLETED";
        }
        if (session.status() == OnboardingStatus.IN_PROGRESS
                && definition.order() == session.currentStepIndex()) {
            return "CURRENT";
        }
        return "PENDING";
    }
}