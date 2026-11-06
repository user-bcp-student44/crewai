// OnboardingValidationException.java
package pe.bcp.login.exception;

import java.util.LinkedHashMap;
import java.util.Map;

public class OnboardingValidationException extends RuntimeException {

    private final transient Map<String, String> fieldErrors;

    public OnboardingValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}