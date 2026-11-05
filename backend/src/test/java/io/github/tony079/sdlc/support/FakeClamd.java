package io.github.tony079.sdlc.support;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A test double of clamd speaking the real INSTREAM protocol (no ClamAV daemon is available in the development
 * container; CI and Docker Compose use the real ClamAV image). Content containing {@link #MARKER} is reported as
 * infected. While {@link #down} is true the server drops connections without a reply, like an unhealthy scanner.
 */
public final class FakeClamd {

    /** Not a real signature; the fake reports it as "Test.Marker FOUND". */
    public static final String MARKER = "SDLC-FAKE-MALWARE-MARKER";

    private static FakeClamd instance;
    public static volatile boolean down;

    private final ServerSocket server;

    private FakeClamd() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-clamd");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized int port() {
        if (instance == null) {
            try {
                instance = new FakeClamd();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance.server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                if (down) {
                    continue;
                }
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] command = in.readNBytes(10);
                if (!new String(command, StandardCharsets.US_ASCII).equals("zINSTREAM\0")) {
                    reply(socket.getOutputStream(), "UNKNOWN COMMAND");
                    continue;
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int length;
                while ((length = in.readInt()) > 0) {
                    content.write(in.readNBytes(length));
                }
                String text = content.toString(StandardCharsets.ISO_8859_1);
                reply(socket.getOutputStream(), text.contains(MARKER) ? "stream: Test.Marker FOUND" : "stream: OK");
            } catch (IOException e) {
                // next connection
            }
        }
    }

    private static void reply(OutputStream out, String text) throws IOException {
        out.write((text + "\0").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
