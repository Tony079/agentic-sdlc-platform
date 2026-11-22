package io.github.tony079.sdlc.sandbox.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.tony079.sdlc.sandbox.api.Devbox;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * GD-36: the devbox HTTP client. The token is sent in a header and never logged; answers are size-checked. Commands
 * may run for up to the devbox's limit (15 minutes), so their requests wait that long.
 */
@Component
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class HttpDevbox implements Devbox {

    private static final Logger log = LoggerFactory.getLogger(HttpDevbox.class);
    static final Set<String> ACTIONS = Set.of("list", "read", "write", "edit", "delete", "search", "exec");
    private static final long MAX_ANSWER = 32L * 1024 * 1024;
    /** The devbox's own upload limit (DEVBOX_MAX_UPLOAD_BYTES). */
    static final int MAX_UPLOAD = 200 * 1024 * 1024;

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final JsonMapper json;

    HttpDevbox(@Value("${sdlc.devbox.url:}") String url, @Value("${sdlc.devbox.token:}") String token, JsonMapper json) {
        this.base = url == null || url.isBlank() ? null : URI.create(url.endsWith("/") ? url : url + "/");
        this.token = token == null ? "" : token;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public boolean enabled() {
        return base != null && token.length() >= 24;
    }

    private HttpRequest.Builder request(String path, Duration timeout) throws DevboxException {
        if (!enabled()) {
            throw new DevboxException("DEVBOX_NOT_CONFIGURED", true);
        }
        return HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest request, boolean refusalsAsJson) throws DevboxException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new DevboxException("DEVBOX_UNREACHABLE", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevboxException("DEVBOX_INTERRUPTED", true);
        }
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes((int) MAX_ANSWER + 1);
        } catch (IOException e) {
            throw new DevboxException("DEVBOX_UNREACHABLE", true);
        }
        if (bytes.length > MAX_ANSWER) {
            throw new DevboxException("DEVBOX_ANSWER_TOO_LARGE", false);
        }
        int status = response.statusCode();
        JsonNode node;
        try {
            node = json.readTree(bytes);
        } catch (RuntimeException e) {
            throw new DevboxException("DEVBOX_INVALID_ANSWER", status >= 500);
        }
        String code = node.path("code").asString("");
        // A missing file or directory is an answer for the model; only a missing workspace is "gone".
        if (status / 100 == 2 || (refusalsAsJson && status / 100 == 4 && status != 401
                && (status != 404 || "PATH_NOT_FOUND".equals(code)))) {
            return node;
        }
        log.warn("devbox answered {} ({})", status, code.matches("^[A-Z_]{1,40}$") ? code : "?");
        if (status == 401) {
            throw new DevboxException("DEVBOX_UNAUTHORISED", false);
        }
        if (status == 404) {
            throw new DevboxException("DEVBOX_WORKSPACE_GONE", false);
        }
        throw new DevboxException(code.matches("^[A-Z_]{1,40}$") ? "DEVBOX_" + code : "DEVBOX_ERROR", status >= 500);
    }

    @Override
    public String create(InputStream tarGz) throws DevboxException {
        byte[] archive;
        try {
            archive = tarGz.readNBytes(MAX_UPLOAD + 1);
        } catch (IOException e) {
            throw new DevboxException("DEVBOX_ARCHIVE_UNREADABLE", true);
        }
        if (archive.length > MAX_UPLOAD) {
            throw new DevboxException("DEVBOX_ARCHIVE_TOO_LARGE", false);
        }
        JsonNode node = send(request("v1/workspaces", Duration.ofMinutes(10))
                .header("Content-Type", "application/gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(archive)).build(), false);
        String id = node.path("id").asString("");
        if (!id.matches("^[0-9a-f]{32}$")) {
            throw new DevboxException("DEVBOX_INVALID_ANSWER", false);
        }
        return id;
    }

    @Override
    public String action(String workspaceId, String action, Map<String, Object> arguments) throws DevboxException {
        if (!ACTIONS.contains(action) || !workspaceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("unknown devbox action");
        }
        Duration timeout = "exec".equals(action) ? Duration.ofMinutes(16) : Duration.ofMinutes(2);
        JsonNode node = send(request("v1/workspaces/" + workspaceId + "/" + action, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(arguments))).build(), true);
        return node.toString();
    }

    @Override
    public List<ChangedFile> changes(String workspaceId) throws DevboxException {
        JsonNode node = send(request("v1/workspaces/" + workspaceId + "/changes", Duration.ofMinutes(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                false);
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode f : node.path("files")) {
            String status = f.path("status").asString("");
            byte[] content = f.hasNonNull("content") ? Base64.getDecoder().decode(f.path("content").asString()) : null;
            files.add(new ChangedFile(f.path("path").asString(""), status, content, f.path("executable").asBoolean()));
        }
        return files;
    }

    @Override
    public void delete(String workspaceId) {
        try {
            send(request("v1/workspaces/" + workspaceId, Duration.ofSeconds(60)).DELETE().build(), false);
        } catch (DevboxException e) {
            log.info("devbox workspace not removed ({})", e.code());
        }
    }

    static Map<String, Object> args(Object... keyValues) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }
}
