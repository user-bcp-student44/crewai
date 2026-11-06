// StartOnboardingRequest.java
package pe.bcp.login.dto;

import jakarta.validation.constraints.Size;

public class StartOnboardingRequest {

    @Size(max = 50, message = "El canal de adquisición no puede superar 50 caracteres.")
    public String acquisitionChannel;
}