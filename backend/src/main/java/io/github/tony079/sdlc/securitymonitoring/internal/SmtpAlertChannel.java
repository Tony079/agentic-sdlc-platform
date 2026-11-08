package io.github.tony079.sdlc.securitymonitoring.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Email delivery (C-17) with a minimal JDK-only SMTP client: implicit TLS (SMTPS, default port 465) with host-name
 * verification, AUTH PLAIN when credentials are configured, one message to the configured recipients. Plain SMTP is
 * accepted only for a loopback host when explicitly allowed (tests). No third-party mail library is used.
 */
class SmtpAlertChannel implements AlertChannel {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final List<String> to;
    private final boolean plaintext;

    SmtpAlertChannel(String host, int port, String username, String password, String from, List<String> to,
                     boolean allowLoopbackPlaintext) {
        this.host = host == null || host.isBlank() ? null : host.strip();
        this.port = port;
        this.username = username == null || username.isBlank() ? null : username;
        this.password = password;
        this.from = from;
        this.to = to == null ? List.of() : to.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        boolean loopback = "localhost".equals(this.host) || "127.0.0.1".equals(this.host);
        this.plaintext = allowLoopbackPlaintext && loopback;
        if (this.host != null && (from == null || !from.contains("@") || this.to.isEmpty())) {
            throw new IllegalStateException("sdlc.alerts.email needs from and to addresses");
        }
        for (String address : this.to) {
            if (!address.matches("^[^@\\s<>\\r\\n]{1,64}@[^@\\s<>\\r\\n]{1,255}$")) {
                throw new IllegalStateException("invalid alert recipient");
            }
        }
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public boolean configured() {
        return host != null;
    }

    @Override
    public void send(String subject, String jsonPayload) throws DeliveryException {
        try (Socket socket = open()) {
            socket.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            expect(in, 220);
            command(out, in, "EHLO sdlc-platform", 250);
            if (username != null) {
                String auth = Base64.getEncoder().encodeToString(("\0" + username + "\0" + password)
                        .getBytes(StandardCharsets.UTF_8));
                command(out, in, "AUTH PLAIN " + auth, 235);
            }
            command(out, in, "MAIL FROM:<" + from + ">", 250);
            for (String recipient : to) {
                command(out, in, "RCPT TO:<" + recipient + ">", 250);
            }
            command(out, in, "DATA", 354);
            String message = "From: " + from + "\r\nTo: " + String.join(", ", to) + "\r\nSubject: "
                    + subject.replaceAll("[\\r\\n]", " ") + "\r\nDate: "
                    + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\nMIME-Version: 1.0\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + jsonPayload.replace("\r\n.", "\r\n..") + "\r\n.";
            command(out, in, message, 250);
            command(out, in, "QUIT", 221);
        } catch (DeliveryException e) {
            throw e;
        } catch (IOException e) {
            throw new DeliveryException("SMTP_UNREACHABLE");
        }
    }

    private Socket open() throws IOException {
        if (plaintext) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5_000);
            return socket;
        }
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        var params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.startHandshake();
        return socket;
    }

    private static void command(OutputStream out, BufferedReader in, String line, int expected)
            throws IOException, DeliveryException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        expect(in, expected);
    }

    private static void expect(BufferedReader in, int expected) throws IOException, DeliveryException {
        String line;
        do {
            line = in.readLine();
            if (line == null || line.length() < 3) {
                throw new DeliveryException("SMTP_PROTOCOL");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(String.valueOf(expected))) {
            throw new DeliveryException("SMTP_REJECTED_" + line.substring(0, 3));
        }
    }
}
