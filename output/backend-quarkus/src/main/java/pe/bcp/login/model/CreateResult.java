// CreateResult.java
package pe.bcp.login.service;

import pe.bcp.login.domain.Payment;

/** created = true si se creo un intento nuevo; false si fue una repeticion idempotente. */
public record CreateResult(Payment payment, boolean created) {
}