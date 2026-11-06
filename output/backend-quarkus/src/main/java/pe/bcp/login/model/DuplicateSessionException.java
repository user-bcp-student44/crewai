// DuplicateSessionException.java
package pe.bcp.login.exception;

public class DuplicateSessionException extends RuntimeException {

    public DuplicateSessionException(String message) {
        super(message);
    }
}