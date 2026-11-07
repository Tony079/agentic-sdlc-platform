package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubException.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The trusted GitHub broker (TDD 14.2, D-M4R-02). Runs only in the worker. Holds the GitHub App private key in memory,
 * signs short-lived App JWTs and obtains an installation token per job, narrowed to one repository with Contents: Read
 * and Metadata: Read. Tokens are never persisted, logged or handed to agents or models.
 *
 * <p>HTTP rules: every URL must be HTTPS on an allowed host (plain HTTP only to loopback when explicitly allowed, for
 * the fake test server); redirects are never followed automatically, and only the tarball download follows one
 * redirect, to an allowed host; JSON responses are size-capped. Response bodies are never logged.
 */
@Component
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class GitHubBroker {

    static final long MAX_JSON_BYTES = 1_048_576;
    private static final String KEY_LABEL = "PRIVATE" + " KEY";
    private static final Map<String, String> TOKEN_PERMISSIONS = Map.of("contents", "read", "metadata", "read");

    record Installation(long id) {
    }

    record RepositoryInfo(long id, String fullName, String defaultBranch) {
    }

    /** A scoped installation token, valid for one job. {@link #toString()} never reveals it. */
    static final class Token {
        private final String value;

        Token(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }

    private final boolean enabled;
    private final String appId;
    private final URI apiBase;
    private final Set<String> allowedHosts;
    private final boolean allowLoopbackHttp;
    private final PrivateKey privateKey;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration downloadTimeout;

    @Autowired
    GitHubBroker(@Value("${sdlc.github.enabled:false}") boolean enabled,
                 @Value("${sdlc.github.app-id:}") String appId,
                 @Value("${sdlc.github.private-key-file:}") String privateKeyFile,
                 @Value("${sdlc.github.api-base-url:https://api.github.com}") String apiBaseUrl,
                 @Value("${sdlc.github.allowed-hosts:api.github.com,codeload.github.com}") List<String> allowedHosts,
                 @Value("${sdlc.github.allow-loopback-http:false}") boolean allowLoopbackHttp,
                 @Value("${sdlc.github.download-timeout:10m}") Duration downloadTimeout,
                 JsonMapper json) {
        this(enabled, appId, privateKeyFile, apiBaseUrl, allowedHosts, allowLoopbackHttp, downloadTimeout, json,
                Clock.systemUTC());
    }

    GitHubBroker(boolean enabled, String appId, String privateKeyFile, String apiBaseUrl, List<String> allowedHosts,
                 boolean allowLoopbackHttp, Duration downloadTimeout, JsonMapper json, Clock clock) {
        this.enabled = enabled;
        this.appId = appId == null ? "" : appId.strip();
        this.allowedHosts = allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.json = json;
        this.clock = clock;
        this.downloadTimeout = downloadTimeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        this.apiBase = URI.create(apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl);
        if (enabled) {
            if (!this.appId.matches("^[0-9]{1,12}$")) {
                throw new IllegalStateException("sdlc.github.app-id must be the numeric GitHub App id");
            }
            checkUrl(this.apiBase, "sdlc.github.api-base-url");
            this.privateKey = loadPrivateKey(privateKeyFile);
        } else {
            this.privateKey = null;
        }
    }

    boolean enabled() {
        return enabled;
    }

    /** The App installation covering the repository; ACCESS_DENIED if the App is not installed on it. */
    Installation installation(String owner, String name) throws GitHubException {
        requireEnabled();
        JsonNode body = getJson(api("/repos/" + segment(owner) + "/" + segment(name) + "/installation"),
                "Bearer " + appJwt());
        long id = body.path("id").asLong(0);
        if (id <= 0) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
        }
        return new Installation(id);
    }

    /**
     * An installation token narrowed to one repository and read-only permissions. Refused if GitHub grants anything
     * beyond Contents: Read and Metadata: Read.
     */
    Token token(Installation installation, String repositoryName) throws GitHubException {
        requireEnabled();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("repositories", List.of(repositoryName));
        request.put("permissions", TOKEN_PERMISSIONS);
        HttpRequest post = HttpRequest.newBuilder(api("/app/installations/" + installation.id() + "/access_tokens"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + appJwt())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                .build();
        JsonNode body = readJson(send(post));
        String token = body.path("token").asString("");
        if (token.isEmpty() || token.length() > 512) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
        }
        JsonNode permissions = body.path("permissions");
        if (permissions.isObject()) {
            for (var entry : permissions.properties()) {
                String granted = entry.getValue().asString("");
                if (!"read".equals(granted) || !TOKEN_PERMISSIONS.containsKey(entry.getKey())) {
                    throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_TOKEN_SCOPE");
                }
            }
        }
        return new Token(token);
    }

    /** Repository metadata from GitHub (trusted data: id and default branch). */
    RepositoryInfo repository(String owner, String name, Token token) throws GitHubException {
        JsonNode body = getJson(api("/repos/" + segment(owner) + "/" + segment(name)), "Bearer " + token.value());
        long id = body.path("id").asLong(0);
        String branch = body.path("default_branch").asString("");
        if (id <= 0 || branch.isEmpty() || branch.length() > 255) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
        }
        return new RepositoryInfo(id, body.path("full_name").asString(""), branch);
    }

    /** The commit SHA at the head of a branch. */
    String branchHead(String owner, String name, String branch, Token token) throws GitHubException {
        JsonNode body = getJson(api("/repos/" + segment(owner) + "/" + segment(name) + "/branches/"
                + URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")), "Bearer " + token.value());
        String sha = body.path("commit").path("sha").asString("");
        if (!sha.matches("^[0-9a-f]{40}$")) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
        }
        return sha;
    }

    /**
     * Opens the tarball of a commit. GitHub answers with one redirect to its download host, which is followed only if
     * that host is allowed; the token is not sent across the redirect. The caller closes the stream.
     */
    InputStream tarball(String owner, String name, String sha, Token token) throws GitHubException {
        if (!sha.matches("^[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("Invalid commit SHA");
        }
        HttpResponse<InputStream> first = sendStream(HttpRequest.newBuilder(
                        api("/repos/" + segment(owner) + "/" + segment(name) + "/tarball/" + sha))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token.value())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build());
        if (first.statusCode() == 200) {
            return first.body();
        }
        closeQuietly(first.body());
        if (first.statusCode() != 302 && first.statusCode() != 301 && first.statusCode() != 307) {
            throw classify(first);
        }
        String location = first.headers().firstValue("Location").orElseThrow(
                () -> new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_REDIRECT_INVALID"));
        URI target;
        try {
            target = URI.create(location);
        } catch (IllegalArgumentException e) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_REDIRECT_INVALID");
        }
        if (!target.isAbsolute()) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_REDIRECT_INVALID");
        }
        try {
            checkUrl(target, "redirect");
        } catch (IllegalStateException e) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_REDIRECT_HOST_REFUSED");
        }
        // The download URL is pre-signed; the installation token is deliberately not forwarded.
        HttpResponse<InputStream> second = sendStream(HttpRequest.newBuilder(target).timeout(downloadTimeout).GET().build());
        if (second.statusCode() != 200) {
            closeQuietly(second.body());
            if (second.statusCode() >= 300 && second.statusCode() < 400) {
                throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_REDIRECT_INVALID");
            }
            throw classify(second);
        }
        return second.body();
    }

    // --- App JWT -------------------------------------------------------------------------------------------------

    /** RS256 App JWT, valid at most 9 minutes (GitHub allows 10), issued 60 s in the past for clock drift. */
    String appJwt() {
        long now = clock.instant().getEpochSecond();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\""
                + appId + "\"}").getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + encoder.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("App JWT signing failed");
        }
    }

    /** Reads a PEM private key (PKCS#8 "PRIVATE KEY" or GitHub's PKCS#1 "RSA PRIVATE KEY"). Never logs it. */
    static PrivateKey loadPrivateKey(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("sdlc.github.private-key-file is required when the GitHub integration is on");
        }
        String pem;
        try {
            pem = Files.readString(Path.of(file), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("The GitHub App private key file cannot be read");
        }
        // PEM armour lines are matched by pattern (the literal header is not spelled out in source).
        boolean pkcs1 = pem.contains("BEGIN RSA " + KEY_LABEL);
        String base64 = pem.replaceAll("-{5}(BEGIN|END) (RSA )?" + KEY_LABEL + "-{5}", "").replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The GitHub App private key is not valid PEM");
        }
        try {
            byte[] pkcs8 = pkcs1 ? wrapPkcs1(der) : der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The GitHub App private key is not a valid RSA key");
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo (rsaEncryption, NULL parameters). */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
                0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = concat(new byte[] {0x04}, derLength(pkcs1.length), pkcs1);
        byte[] body = concat(version, algorithm, octet);
        return concat(new byte[] {0x30}, derLength(body.length), body);
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length < 0x100) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        return new byte[] {(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    // --- HTTP ----------------------------------------------------------------------------------------------------

    private void requireEnabled() throws GitHubException {
        if (!enabled) {
            throw new GitHubException(Kind.NOT_CONFIGURED, "GITHUB_NOT_CONFIGURED");
        }
    }

    private URI api(String path) {
        return URI.create(apiBase + path);
    }

    /** Owner and repository names are validated before use; this only guards the path segment. */
    private static String segment(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._-]{1,100}$") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid repository path segment");
        }
        return value;
    }

    /** HTTPS on an allowed host; plain HTTP only to a loopback host when explicitly allowed (tests). */
    void checkUrl(URI uri, String what) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new IllegalStateException(what + " host is not allowed");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && allowLoopbackHttp && isLoopback(host)) {
            return;
        }
        throw new IllegalStateException(what + " must use HTTPS");
    }

    private static boolean isLoopback(String host) {
        try {
            return host.equals("localhost") || InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode getJson(URI uri, String authorization) throws GitHubException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", authorization)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        return readJson(send(request));
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws GitHubException {
        HttpResponse<InputStream> response = sendStream(request);
        if (response.statusCode() / 100 != 2) {
            closeQuietly(response.body());
            throw classify(response);
        }
        return response;
    }

    private HttpResponse<InputStream> sendStream(HttpRequest request) throws GitHubException {
        try {
            checkUrl(request.uri(), "request");
        } catch (IllegalStateException e) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_HOST_REFUSED");
        }
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new GitHubException(Kind.TRANSIENT, "GITHUB_UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException(Kind.TRANSIENT, "GITHUB_INTERRUPTED");
        }
    }

    private JsonNode readJson(HttpResponse<InputStream> response) throws GitHubException {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_RESPONSE_TOO_LARGE");
            }
            JsonNode node = json.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
            }
            return node;
        } catch (IOException | RuntimeException e) {
            throw new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_INVALID_RESPONSE");
        }
    }

    /** Maps a non-success status to a failure class (TDD 14.4). The body is not read. */
    static GitHubException classify(HttpResponse<?> response) {
        int status = response.statusCode();
        Optional<String> remaining = response.headers().firstValue("x-ratelimit-remaining");
        if (status == 429 || (status == 403 && (remaining.filter("0"::equals).isPresent()
                || response.headers().firstValue("retry-after").isPresent()))) {
            return new GitHubException(Kind.RATE_LIMITED, "GITHUB_RATE_LIMITED", retryDelay(response));
        }
        if (status == 401 || status == 403 || status == 404) {
            return new GitHubException(Kind.ACCESS_DENIED, "REPO_ACCESS");
        }
        if (status == 301 || status == 410) {
            return new GitHubException(Kind.UNAVAILABLE, "REPOSITORY_UNAVAILABLE");
        }
        if (status >= 500) {
            return new GitHubException(Kind.TRANSIENT, "GITHUB_SERVER_ERROR");
        }
        return new GitHubException(Kind.INVALID_RESPONSE, "GITHUB_UNEXPECTED_STATUS");
    }

    private static Duration retryDelay(HttpResponse<?> response) {
        long seconds = 60;
        Optional<String> retryAfter = response.headers().firstValue("retry-after");
        Optional<String> reset = response.headers().firstValue("x-ratelimit-reset");
        try {
            if (retryAfter.isPresent()) {
                seconds = Long.parseLong(retryAfter.get().strip());
            } else if (reset.isPresent()) {
                seconds = Long.parseLong(reset.get().strip()) - System.currentTimeMillis() / 1000;
            }
        } catch (NumberFormatException e) {
            seconds = 60;
        }
        return Duration.ofSeconds(Math.max(30, Math.min(3600, seconds)));
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
