package io.github.tony079.sdlc.common.mail;

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
import java.util.UUID;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import io.github.tony079.sdlc.common.api.MailSender;
import io.github.tony079.sdlc.common.api.SecretsProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JDK-only SMTP client for account email (GD-04): implicit TLS (SMTPS) with host-name verification and AUTH PLAIN.
 * Works with Gmail (smtp.gmail.com, port 465, a Google account with 2-Step Verification and an App Password) and with
 * any other SMTPS service. Messages are multipart text and HTML in UTF-8.
 *
 * <p>Configuration: {@code sdlc.mail.host}, {@code .port} (465), {@code .username}, {@code .from},
 * {@code .from-name}; the password is the secret {@code mail.smtp-password} from the {@link SecretsProvider} (never a
 * plain property). Without a host the sender is unconfigured. Plain SMTP is allowed only to a loopback host and only
 * when {@code sdlc.mail.allow-loopback-plaintext=true} (tests).
 */
@Component
public class SmtpMailSender implements MailSender {

    static final String PASSWORD_SECRET = "mail.smtp-password";
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s<>\"\\r\\n]{1,64}@[^@\\s<>\"\\r\\n]{1,255}$");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final String fromName;
    private final boolean plaintext;

    SmtpMailSender(@Value("${sdlc.mail.host:}") String host, @Value("${sdlc.mail.port:465}") int port,
                   @Value("${sdlc.mail.username:}") String username, @Value("${sdlc.mail.from:}") String from,
                   @Value("${sdlc.mail.from-name:Garuda}") String fromName,
                   @Value("${sdlc.mail.allow-loopback-plaintext:false}") boolean allowLoopbackPlaintext,
                   SecretsProvider secrets) {
        this.host = host == null || host.isBlank() ? null : host.strip();
        this.port = port;
        this.username = username == null || username.isBlank() ? null : username.strip();
        this.password = secrets.secret(PASSWORD_SECRET).orElse(null);
        this.from = from == null || from.isBlank() ? this.username : from.strip();
        this.fromName = fromName == null ? "Garuda" : fromName.replaceAll("[\\r\\n\"<>]", " ").strip();
        boolean loopback = "localhost".equals(this.host) || "127.0.0.1".equals(this.host);
        this.plaintext = allowLoopbackPlaintext && loopback;
        if (this.host != null) {
            if (this.from == null || !ADDRESS.matcher(this.from).matches()) {
                throw new IllegalStateException("sdlc.mail.from (or sdlc.mail.username) must be an email address");
            }
            if (this.username != null && this.password == null) {
                throw new IllegalStateException("the secret " + PASSWORD_SECRET + " is required with sdlc.mail.username");
            }
        }
    }

    @Override
    public boolean configured() {
        return host != null;
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) throws MailException {
        if (!configured()) {
            throw new MailException("MAIL_NOT_CONFIGURED");
        }
        if (to == null || !ADDRESS.matcher(to).matches()) {
            throw new MailException("MAIL_INVALID_RECIPIENT");
        }
        try (Socket socket = open()) {
            socket.setSoTimeout(15_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            expect(in, 220);
            command(out, in, "EHLO garuda", 250);
            if (username != null) {
                String auth = Base64.getEncoder().encodeToString(("\0" + username + "\0" + password)
                        .getBytes(StandardCharsets.UTF_8));
                command(out, in, "AUTH PLAIN " + auth, 235);
            }
            command(out, in, "MAIL FROM:<" + from + ">", 250);
            command(out, in, "RCPT TO:<" + to + ">", 250);
            command(out, in, "DATA", 354);
            command(out, in, message(to, subject, textBody, htmlBody), 250);
            command(out, in, "QUIT", 221);
        } catch (MailException e) {
            throw e;
        } catch (IOException e) {
            throw new MailException("MAIL_UNREACHABLE");
        }
    }

    String message(String to, String subject, String textBody, String htmlBody) {
        String boundary = "garuda-" + UUID.randomUUID();
        StringBuilder m = new StringBuilder()
                .append("From: ").append(encodeWord(fromName)).append(" <").append(from).append(">\r\n")
                .append("To: <").append(to).append(">\r\n")
                .append("Subject: ").append(encodeWord(subject.replaceAll("[\\r\\n]", " "))).append("\r\n")
                .append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n")
                .append("Message-ID: <").append(UUID.randomUUID()).append('@').append(from.substring(from.indexOf('@') + 1))
                .append(">\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        part(m, boundary, "text/plain", textBody);
        part(m, boundary, "text/html", htmlBody);
        m.append("--").append(boundary).append("--\r\n.");
        return m.toString();
    }

    private static void part(StringBuilder m, String boundary, String type, String body) {
        m.append("--").append(boundary).append("\r\n")
                .append("Content-Type: ").append(type).append("; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(Base64.getMimeEncoder(76, new byte[] {'\r', '\n'})
                        .encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                .append("\r\n");
    }

    /** RFC 2047 encoded word for non-ASCII header text. */
    private static String encodeWord(String text) {
        if (text.chars().allMatch(c -> c >= 32 && c < 127)) {
            return text;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private Socket open() throws IOException {
        if (plaintext) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 10_000);
            return socket;
        }
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        var params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.startHandshake();
        return socket;
    }

    private static void command(OutputStream out, BufferedReader in, String line, int expected)
            throws IOException, MailException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        expect(in, expected);
    }

    private static void expect(BufferedReader in, int expected) throws IOException, MailException {
        String line;
        do {
            line = in.readLine();
            if (line == null || line.length() < 3) {
                throw new MailException("MAIL_PROTOCOL");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(String.valueOf(expected))) {
            throw new MailException("MAIL_REJECTED_" + line.substring(0, 3));
        }
    }
}
