// CorrelationIds.java
package pe.bcp.login.resource;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private CorrelationIds() {
    }

    /** Devuelve el valor recibido si es seguro; en caso contrario genera uno nuevo. */
    public static String resolve(String headerValue) {
        if (headerValue != null && SAFE.matcher(headerValue).matches()) {
            return headerValue;
        }
        return UUID.randomUUID().toString();
    }
}