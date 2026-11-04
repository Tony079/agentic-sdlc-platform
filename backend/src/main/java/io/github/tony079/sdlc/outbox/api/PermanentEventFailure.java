package io.github.tony079.sdlc.outbox.api;

/** An event that can never be processed successfully (for example an unsupported payload version). */
public class PermanentEventFailure extends RuntimeException {

    private final String code;

    public PermanentEventFailure(String code) {
        super(code);
        this.code = code;
    }

    /** Content-free error code, upper case with underscores. */
    public String code() {
        return code;
    }
}
