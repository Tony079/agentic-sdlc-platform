package io.github.tony079.sdlc.scanner.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.github.tony079.sdlc.scanner.api.MalwareScanner.ScannerUnavailableException;
import io.github.tony079.sdlc.support.FakeClamd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** clamd INSTREAM client: only an explicit OK is clean; everything else fails closed (PRD FR-STO-11/12). */
class ClamdScannerTest {

    @AfterEach
    void up() {
        FakeClamd.down = false;
    }

    private static ClamdScanner scanner(int port) {
        return new ClamdScanner("127.0.0.1", port, Duration.ofSeconds(2));
    }

    @Test
    void parsesRepliesStrictly() throws Exception {
        assertThat(ClamdScanner.parse("stream: OK").clean()).isTrue();
        assertThat(ClamdScanner.parse("stream: Eicar-Signature FOUND").signature()).isEqualTo("Eicar-Signature");
        assertThatThrownBy(() -> ClamdScanner.parse("INSTREAM size limit exceeded. ERROR"))
                .isInstanceOf(ScannerUnavailableException.class);
        assertThatThrownBy(() -> ClamdScanner.parse("")).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void streamsContentOverTheProtocol() throws Exception {
        int port = FakeClamd.port();
        assertThat(scanner(port).scan(new ByteArrayInputStream("benign".getBytes(StandardCharsets.UTF_8))).clean()).isTrue();
        assertThat(scanner(port).scan(new ByteArrayInputStream(("x" + FakeClamd.MARKER).getBytes(StandardCharsets.UTF_8)))
                .clean()).isFalse();
    }

    @Test
    void unreachableOrSilentScannerIsUnavailableNeverClean() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> scanner(closedPort).scan(new ByteArrayInputStream(new byte[10])))
                .isInstanceOf(ScannerUnavailableException.class);
        FakeClamd.down = true;
        assertThatThrownBy(() -> scanner(FakeClamd.port()).scan(new ByteArrayInputStream(new byte[10])))
                .isInstanceOf(ScannerUnavailableException.class);
    }
}
