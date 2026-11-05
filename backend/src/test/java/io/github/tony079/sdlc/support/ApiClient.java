package io.github.tony079.sdlc.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser-like HTTP client against the running server: its own cookie jar (session + CSRF cookies),
 * fetches the CSRF token first and echoes it in X-CSRF-Token on every mutating request.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public record Response(int status, JsonNode body, Map<String, java.util.List<String>> headers) {

        public String errorCode() {
            return body == null ? null : body.path("error").path("code").asString();
        }

        /** Field-level error codes from a 422 response, as "field:CODE". */
        public java.util.List<String> fieldErrors() {
            if (body == null) {
                return java.util.List.of();
            }
            return body.path("error").path("details").valueStream()
                    .map(d -> d.path("field").asString() + ":" + d.path("code").asString())
                    .toList();
        }

        public String header(String name) {
            java.util.List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    public Response get(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        headers.forEach(builder::header);
        return send(builder.build());
    }

    /** POST with the current CSRF token. */
    public Response post(String path, Object body) {
        return post(path, body, Map.of(), true);
    }

    public Response post(String path, Object body, Map<String, String> headers, boolean withCsrf) {
        if (withCsrf && csrfCookie().isEmpty()) {
            get("/api/v1/auth/csrf");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : JSON.writeValueAsString(body)));
        if (withCsrf) {
            builder.header("X-CSRF-Token", csrfCookie().orElseThrow());
        }
        headers.forEach(builder::header);
        return send(builder.build());
    }

    /** PUT with the current CSRF token and extra headers (for example If-Match). */
    public Response put(String path, Object body, Map<String, String> headers) {
        if (csrfCookie().isEmpty()) {
            get("/api/v1/auth/csrf");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrfCookie().orElseThrow())
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    /** Multipart upload of one file in part "file", with the CSRF token. */
    public Response upload(String path, String fileName, String contentType, byte[] content) {
        if (csrfCookie().isEmpty()) {
            get("/api/v1/auth/csrf");
        }
        String boundary = "----sdlc-test-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\n" + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n") + "\r\n";
        body.writeBytes(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-Token", csrfCookie().orElseThrow())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
    }

    public Response delete(String path) {
        if (csrfCookie().isEmpty()) {
            get("/api/v1/auth/csrf");
        }
        return send(HttpRequest.newBuilder(uri(path)).header("X-CSRF-Token", csrfCookie().orElseThrow()).DELETE().build());
    }

    public record Download(int status, byte[] body, Map<String, java.util.List<String>> headers) {
        public String header(String name) {
            java.util.List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public Download download(String path) {
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new Download(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Response login(String organisation, String email, String password) {
        return post("/api/v1/auth/login", Map.of("organisation", organisation, "email", email, "password", password));
    }

    public Optional<String> sessionId() {
        return cookie("SDLC_SESSION");
    }

    private Optional<String> csrfCookie() {
        return cookie("XSRF-TOKEN");
    }

    private Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name) && !c.hasExpired() && !c.getValue().isEmpty())
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode json = text == null || text.isBlank() ? null : JSON.readTree(text);
            return new Response(response.statusCode(), json, response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
