// PaymentEventPublisherPort.java
package pe.bcp.login.port;

import pe.bcp.login.domain.Payment;

/** ADAPTADOR PENDIENTE: el destino/formato de eventos (activacion MAU, funnel) no fue especificado. */
public interface PaymentEventPublisherPort {

    /** Evento de activacion usado para MAU. Solo para pagos SUCCEEDED. */
    void publishActivation(Payment payment);

    void publishFunnel(String eventName, Payment payment);
}