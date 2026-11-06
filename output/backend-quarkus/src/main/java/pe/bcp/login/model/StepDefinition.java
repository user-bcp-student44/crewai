// StepDefinition.java
package pe.bcp.login.model;

public record StepDefinition(String stepId, int order, String label, boolean required) {
}