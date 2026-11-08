package io.github.tony079.sdlc.securitymonitoring.internal;

/**
 * One alert delivery channel (C-17). The payload is content-free JSON (alert id, rule, event type, severity, times,
 * count, correlation id, environment): never passwords, tokens, secrets, personal data or document content.
 */
interface AlertChannel {

    /** EMAIL or WEBHOOK. */
    String name();

    boolean configured();

    /** Throws {@link DeliveryException} with a content-free code on failure. */
    void send(String subject, String jsonPayload) throws DeliveryException;

    final class DeliveryException extends Exception {
        private final String code;

        DeliveryException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
