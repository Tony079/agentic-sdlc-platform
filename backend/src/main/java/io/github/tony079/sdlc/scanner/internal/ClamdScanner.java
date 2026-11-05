package io.github.tony079.sdlc.scanner.internal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.github.tony079.sdlc.scanner.api.MalwareScanner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ClamAV client using the clamd INSTREAM protocol over TCP (TDD 15.3, AC-EXT-35). Content is streamed in chunks;
 * only an explicit "OK" reply is clean. Any connection failure, timeout, size-limit or error reply is reported as
 * unavailable, so the caller keeps the file quarantined (fail closed).
 */
@Component
class ClamdScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;

    private final String host;
    private final int port;
    private final Duration timeout;

    ClamdScanner(@Value("${sdlc.scanner.clamd.host:localhost}") String host,
                 @Value("${sdlc.scanner.clamd.port:3310}") int port,
                 @Value("${sdlc.scanner.clamd.timeout:60s}") Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "clamav";
    }

    @Override
    public Verdict scan(InputStream content) throws ScannerUnavailableException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Math.min(timeout.toMillis(), 10_000));
            socket.setSoTimeout((int) timeout.toMillis());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = content.read(buffer)) > 0) {
                out.writeInt(read);
                out.write(buffer, 0, read);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            throw new ScannerUnavailableException("Scanner unreachable or failed", e);
        }
    }

    static Verdict parse(String reply) throws ScannerUnavailableException {
        // Replies: "stream: OK", "stream: <signature> FOUND", "INSTREAM size limit exceeded. ERROR", "... ERROR".
        String text = reply.strip();
        if (text.equals("stream: OK")) {
            return Verdict.cleanResult();
        }
        if (text.startsWith("stream: ") && text.endsWith(" FOUND")) {
            String signature = text.substring("stream: ".length(), text.length() - " FOUND".length()).strip();
            return Verdict.infected(signature.isEmpty() ? "UNKNOWN" : signature);
        }
        throw new ScannerUnavailableException("Unexpected scanner reply", null);
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            reply.write(b);
            if (reply.size() > 1024) {
                throw new IOException("Scanner reply too long");
            }
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
