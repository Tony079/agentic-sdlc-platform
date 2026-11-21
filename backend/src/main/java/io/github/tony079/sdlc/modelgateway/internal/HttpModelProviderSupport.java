package io.github.tony079.sdlc.modelgateway.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider.Failure;

/**
 * Shared HTTP plumbing for hosted provider adapters (O-01): HTTPS only (loopback HTTP only when explicitly allowed for
 * tests), no redirects, a 4 MiB response cap, and status mapping to the gateway's failure kinds. Never logs request or
 * response bodies or keys.
 */
final class HttpModelProviderSupport {

    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpModelProviderSupport() {
    }

    static URI endpoint(String baseUrl, String path, boolean allowLoopbackHttp) {
        URI base = URI.create(baseUrl.strip().replaceAll("/+$", "") + path);
        boolean loopback = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if (!"https".equals(base.getScheme()) && !(allowLoopbackHttp && loopback && "http".equals(base.getScheme()))) {
            throw new IllegalStateException("model provider base URL must use HTTPS");
        }
        return base;
    }

    static String post(URI uri, Map<String, String> headers, String body, Duration timeout) throws Failure {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new Failure(Failure.Kind.TIMEOUT);
        } catch (IOException e) {
            throw new Failure(Failure.Kind.UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Kind.TIMEOUT);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new Failure(Failure.Kind.RATE_LIMITED);
        }
        if (status >= 500) {
            throw new Failure(Failure.Kind.UNAVAILABLE);
        }
        if (status != 200 || response.body().length > MAX_RESPONSE_BYTES) {
            logRefusal(uri, status, response.body());
            throw new Failure(Failure.Kind.ERROR);
        }
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Logs why a provider refused a request: the HTTP status and the error's code, type and parameter name (short
     * identifiers), never its message, which can quote the request's data.
     */
    static void logRefusal(URI uri, int status, byte[] body) {
        String code = "";
        String type = "";
        String param = "";
        try {
            tools.jackson.databind.JsonNode error = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(body).path("error");
            code = identifier(error.path("code").asString(""));
            type = identifier(error.path("type").asString(""));
            param = identifier(error.path("param").asString(""));
        } catch (RuntimeException ignored) {
            // not JSON: the status is enough
        }
        org.slf4j.LoggerFactory.getLogger(HttpModelProviderSupport.class).warn(
                "Model provider refused the request: HTTP {} from {} (code={}, type={}, param={})", status,
                uri.getHost(), code, type, param);
    }

    private static String identifier(String value) {
        return value != null && value.matches("^[A-Za-z0-9_.\\[\\]-]{1,80}$") ? value : "";
    }

    /**
     * GD-29: a streamed response (server-sent events), passed line by line to {@code onLine}. Same status mapping and
     * total size cap as {@link #post}; the connection timeout covers the wait for the response headers.
     */
    static void postStream(URI uri, Map<String, String> headers, String body, Duration timeout,
                           java.util.function.Consumer<String> onLine) throws Failure {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException e) {
            org.slf4j.LoggerFactory.getLogger(HttpModelProviderSupport.class).warn("model stream timed out waiting for the response");
            throw new Failure(Failure.Kind.TIMEOUT);
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(HttpModelProviderSupport.class).warn("model stream could not start: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            throw new Failure(Failure.Kind.UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Kind.TIMEOUT);
        }
        try (java.util.stream.Stream<String> lines = response.body()) {
            int status = response.statusCode();
            if (status == 429) {
                throw new Failure(Failure.Kind.RATE_LIMITED);
            }
            if (status >= 500) {
                throw new Failure(Failure.Kind.UNAVAILABLE);
            }
            if (status != 200) {
                org.slf4j.LoggerFactory.getLogger(HttpModelProviderSupport.class).warn("model stream refused: HTTP {}",
                        status);
                throw new Failure(Failure.Kind.ERROR);
            }
            long[] total = {0};
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                total[0] += line.length();
                if (total[0] > MAX_RESPONSE_BYTES) {
                    throw new Failure(Failure.Kind.ERROR);
                }
                onLine.accept(line);
                if (Thread.currentThread().isInterrupted()) {
                    throw new Failure(Failure.Kind.TIMEOUT);
                }
            }
        } catch (java.io.UncheckedIOException e) {
            org.slf4j.LoggerFactory.getLogger(HttpModelProviderSupport.class).warn("model stream interrupted: {}: {}",
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName(),
                    e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage()));
            throw new Failure(Failure.Kind.UNAVAILABLE);
        }
    }

    /** Untrusted content travels as a labelled data block, never mixed into the instructions (TDD 12.7). */
    static String dataText(DataBlock block) {
        String label = block.label().replaceAll("[\"<>\\r\\n]", "_");
        return "<untrusted_data label=\"" + label + "\">\n" + block.content() + "\n</untrusted_data>";
    }
}
