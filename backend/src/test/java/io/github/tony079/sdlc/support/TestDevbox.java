package io.github.tony079.sdlc.support;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * GD-36 (TEST ONLY): the real devbox server (devbox/server.py), started once per test run on a free local port with a
 * temporary workspace root and a random token. Needs python3 and git on the machine running the tests.
 */
public final class TestDevbox {

    private static TestDevbox instance;
    private final String url;
    private final String token;

    private TestDevbox() throws IOException, InterruptedException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path root = Files.createTempDirectory("devbox-test");
        token = UUID.randomUUID().toString().replace("-", "") + "x";
        Path script = Path.of("..", "devbox", "server.py").toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder("python3", script.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("DEVBOX_TOKEN", token);
        builder.environment().put("DEVBOX_PORT", String.valueOf(port));
        builder.environment().put("DEVBOX_ROOT", root.toString());
        Process process = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        url = "http://127.0.0.1:" + port;
        for (int i = 0; i < 100; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(url + "/health").toURL().openConnection();
                if (c.getResponseCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // starting
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("devbox test server did not start");
    }

    public static synchronized TestDevbox get() {
        if (instance == null) {
            try {
                instance = new TestDevbox();
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public String url() {
        return url;
    }

    public String token() {
        return token;
    }
}
