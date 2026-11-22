package io.github.tony079.sdlc.sandbox.api;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * GD-36 (worker only): the Developer agent's sandbox. A workspace holds one story's copy of the repository; client code
 * is built and tested there, never in the worker. The sandbox holds no credentials and reaches only package
 * registries. Arguments that come from the model are passed through as untrusted input; the sandbox checks them.
 */
public interface Devbox {

    /** Whether the sandbox is configured on this worker. */
    boolean enabled();

    /** Creates a workspace from a GitHub tarball of the base commit; returns its id. The caller closes the stream. */
    String create(InputStream tarGz) throws DevboxException;

    /**
     * Runs a workspace action ({@code list}, {@code read}, {@code write}, {@code edit}, {@code delete}, {@code search},
     * {@code exec}) and returns its JSON answer. A refusal (bad path, command not allowed) is returned as JSON with a
     * {@code code} field, not thrown: the model is told and can correct itself.
     */
    String action(String workspaceId, String action, Map<String, Object> arguments) throws DevboxException;

    /** The files changed in the workspace since its base, with their content. */
    List<ChangedFile> changes(String workspaceId) throws DevboxException;

    /** Removes the workspace; quiet if it is already gone. */
    void delete(String workspaceId);

    /** A changed file: content is null when the file was deleted. */
    record ChangedFile(String path, String status, byte[] content, boolean executable) {
    }

    /** The sandbox could not be reached or answered unexpectedly; the code is content-free. */
    final class DevboxException extends Exception {
        private final String code;
        private final boolean retryable;

        public DevboxException(String code, boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
