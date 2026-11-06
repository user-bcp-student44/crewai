// StartResult.java
package pe.bcp.login.service;

import pe.bcp.login.model.OnboardingSession;

public record StartResult(OnboardingSession session, boolean created) {
}