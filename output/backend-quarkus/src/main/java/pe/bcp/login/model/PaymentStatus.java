// PaymentStatus.java
package pe.bcp.login.domain;

public enum PaymentStatus {
    PENDING(false),
    SUCCEEDED(true),
    FAILED(true),
    EXPIRED(true);

    private final boolean terminal;

    PaymentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}