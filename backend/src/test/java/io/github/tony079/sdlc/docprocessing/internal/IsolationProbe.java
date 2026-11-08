package io.github.tony079.sdlc.docprocessing.internal;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/** TEST ONLY: run by ExtractorIsolationTest through the extractor launcher; reports what the sandbox allows. */
public final class IsolationProbe {

    private IsolationProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        long write = Long.parseLong(args[2]);
        boolean network;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            network = true;
        } catch (Exception e) {
            network = false;
        }
        Files.writeString(report, "network=" + network + "\nenv=" + System.getenv().size() + "\n");
        try (OutputStream out = Files.newOutputStream(report.resolveSibling("big.bin"))) {
            byte[] block = new byte[64 * 1024];
            for (long written = 0; written < write; written += block.length) {
                out.write(block);
            }
        } catch (Exception e) {
            Files.writeString(report, "fsize=blocked\n", java.nio.file.StandardOpenOption.APPEND);
        }
    }
}
