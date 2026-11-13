package io.github.tony079.sdlc.common.api;

/**
 * Outgoing email for account administration only: invitations and password resets (Garuda GD-04; AC-EXT-40 is
 * otherwise unchanged, user notifications stay in-app). When no SMTP server is configured, {@link #configured()} is
 * false and callers fall back to showing the link to the administrator (copy-link).
 */
public interface MailSender {

    boolean configured();

    /**
     * Sends one message to one recipient.
     *
     * @throws MailException with a content-free code when the message could not be handed to the SMTP server
     */
    void send(String to, String subject, String textBody, String htmlBody) throws MailException;

    /** A delivery failure; the code never contains the message, the recipient or a credential. */
    final class MailException extends Exception {
        private final String code;

        public MailException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
