package io.github.tony079.sdlc.securitymonitoring.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook delivery (C-17): HTTPS POST of the JSON payload with an HMAC-SHA256 signature header
 * ({@code X-Sdlc-Signature: sha256=...}) over the body, keyed with the configured secret. Plain HTTP is accepted only
 * for a loopback host when explicitly allowed (tests). Redirects are not followed.
 */
class WebhookAlertChannel implements AlertChannel {

    private final URI url;
    private final byte[] secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    WebhookAlertChannel(String url, String secret, boolean allowLoopbackHttp) {
        if (url == null || url.isBlank()) {
            this.url = null;
            this.secret = null;
            return;
        }
        URI parsed = URI.create(url.strip());
        boolean loopback = "localhost".equals(parsed.getHost()) || "127.0.0.1".equals(parsed.getHost());
        if (!"https".equals(parsed.getScheme()) && !(allowLoopbackHttp && loopback && "http".equals(parsed.getScheme()))) {
            throw new IllegalStateException("sdlc.alerts.webhook.url must use HTTPS");
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("sdlc.alerts.webhook.secret must be set (at least 32 characters)");
        }
        this.url = parsed;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "WEBHOOK";
    }

    @Override
    public boolean configured() {
        return url != null;
    }

    @Override
    public void send(String subject, String jsonPayload) throws DeliveryException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(jsonPayload.getBytes(StandardCharsets.UTF_8)));
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-Sdlc-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new DeliveryException("WEBHOOK_HTTP_" + response.statusCode());
            }
        } catch (DeliveryException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("WEBHOOK_INTERRUPTED");
        } catch (Exception e) {
            throw new DeliveryException("WEBHOOK_UNREACHABLE");
        }
    }
}
