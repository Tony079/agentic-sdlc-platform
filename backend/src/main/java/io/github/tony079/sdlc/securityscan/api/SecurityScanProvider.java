package io.github.tony079.sdlc.securityscan.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Provider-neutral security scanner port (owner decision O-03). A provider scans the pinned snapshot statically: it
 * receives file contents through {@link Target} and must never execute repository code or reach the network unless
 * that is its documented, configured function. Results are normalised; a provider that could not run returns
 * UNAVAILABLE or FAILED, never COMPLETED. Workflow logic depends only on this port.
 */
public interface SecurityScanProvider {

    enum Kind { DEPENDENCY, SECRET, SAST }

    enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    /** Stable provider name, {@code ^[a-z][a-z0-9_.-]{1,63}$}. */
    String name();

    String version();

    Kind kind();

    Result scan(Target target);

    interface Target {
        UUID snapshotId();

        String commitSha();

        int forEachFile(Predicate<String> paths, BiConsumer<String, byte[]> visitor) throws IOException;
    }

    /**
     * @param evidence    masked, never the secret or sensitive value itself (at most 200 characters)
     * @param fingerprint SHA-256 of the matched value, to recognise the same finding without storing it
     */
    record Finding(Severity severity, String ruleId, String category, String path, Integer line, String evidence,
                   String fingerprint) {
    }

    record Result(String status, String errorCode, int filesScanned, List<Finding> findings) {
        public static Result completed(int files, List<Finding> findings) {
            return new Result("COMPLETED", null, files, List.copyOf(findings));
        }

        public static Result unavailable(String code) {
            return new Result("UNAVAILABLE", code, 0, List.of());
        }

        public static Result failed(String code, int files) {
            return new Result("FAILED", code, files, List.of());
        }
    }
}
