package io.github.tony079.sdlc.common.api;

import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Correlation id of the current request, carried in the logging MDC and written to audit and security events. */
public final class CorrelationId {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private CorrelationId() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /** Accepts a client-supplied id only if it is a short, safe token; otherwise the caller generates one. */
    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }
}
