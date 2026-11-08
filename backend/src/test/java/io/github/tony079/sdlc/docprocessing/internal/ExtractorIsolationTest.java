package io.github.tony079.sdlc.docprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O-15 (owner decision 2026-11-08): the extractor process gets no network (separate network namespace), no
 * environment (no secrets or credentials), a file-size limit, and a time limit; a required sandbox that cannot be
 * created makes extraction refuse to run. Uses a probe program through the real launcher (TEST ONLY).
 */
class ExtractorIsolationTest {

    private static final long LIMIT = 1024 * 1024;

    private static ExtractorLauncher sandboxed() {
        return new ExtractorLauncher(Duration.ofSeconds(60), "64m", "unshare-net", true, LIMIT);
    }

    private static String probe(ExtractorLauncher launcher, Path dir, int port) throws Exception {
        Path report = dir.resolve("report.txt");
        launcher.run(IsolationProbe.class.getName(), List.of(report.toString(), String.valueOf(port),
                String.valueOf(4 * LIMIT)), dir);
        return Files.readString(report);
    }

    @Test
    void theSandboxHasNoNetworkNoEnvironmentAndAFileSizeLimit(@TempDir Path dir) throws Exception {
        ExtractorLauncher launcher = sandboxed();
        assumeTrue(launcher.sandboxAvailable(), "user and network namespaces are not available on this host");
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        server.accept().close();
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
            // Control: without the sandbox the same probe reaches the worker's loopback server.
            String open = probe(new ExtractorLauncher(Duration.ofSeconds(60), "64m"), Files.createDirectories(
                    dir.resolve("control")), server.getLocalPort());
            assertThat(open).contains("network=true").contains("env=0");
            String isolated = probe(launcher, Files.createDirectories(dir.resolve("sandbox")), server.getLocalPort());
            assertThat(isolated).contains("network=false").contains("env=0");
            Path big = dir.resolve("sandbox").resolve("big.bin");
            assertThat(!Files.exists(big) || Files.size(big) <= LIMIT).isTrue();
        }
        assertThat(launcher.sandboxPrefix()).contains("--net", "--fsize=" + LIMIT);
    }

    @Test
    void aRequiredSandboxIsNeverSilentlySkipped() {
        assertThatThrownBy(() -> new ExtractorLauncher(Duration.ofSeconds(5), "64m", "none", true, LIMIT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ExtractorLauncher(Duration.ofSeconds(5), "64m", "docker", false, LIMIT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ExtractorLauncher(Duration.ofSeconds(5), "64m").sandboxPrefix()).isEmpty();
    }
}
