package io.github.tony079.sdlc.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/**
 * An in-process fake of the GitHub REST API used by the broker (TEST ONLY). The App key pair is generated at runtime
 * and its private key written to a temporary file; nothing is committed. It verifies App JWT signatures and
 * installation tokens, records token requests, and serves tarballs through a redirect to a second loopback host, like
 * api.github.com and codeload.github.com. Passing tests against it says nothing about real GitHub.
 */
public final class FakeGitHub {

    public static final String APP_ID = "424242";
    private static final long INSTALLATION_ID = 777;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static FakeGitHub instance;

    /** A repository known to the fake. */
    public record Repo(String owner, String name, long id, String defaultBranch, String headSha, byte[] tarball,
                       boolean installed) {
    }

    private final HttpServer api;
    private final HttpServer codeload;
    private final PublicKey publicKey;
    private final Path privateKeyFile;
    private final Map<String, Repo> repos = new ConcurrentHashMap<>();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final List<String> tokenRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> codeloadAuthorization = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger rateLimitNext = new AtomicInteger();
    private final AtomicInteger serverErrorNext = new AtomicInteger();
    private volatile String redirectOverride;
    private volatile Map<String, String> grantedPermissions;

    private FakeGitHub() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = pair.getPublic();
        privateKeyFile = Files.createTempFile("fake-github-app", ".pem");
        privateKeyFile.toFile().deleteOnExit();
        Files.writeString(privateKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        api = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        api.createContext("/", this::handleApi);
        api.start();
        codeload = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        codeload.createContext("/", this::handleCodeload);
        codeload.start();
    }

    public static synchronized FakeGitHub get() {
        if (instance == null) {
            try {
                instance = new FakeGitHub();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public String apiBaseUrl() {
        return "http://localhost:" + api.getAddress().getPort();
    }

    public Path privateKeyFile() {
        return privateKeyFile;
    }

    public void put(Repo repo) {
        repos.put(key(repo.owner(), repo.name()), repo);
    }

    public List<String> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    public List<String> codeloadAuthorizationHeaders() {
        return List.copyOf(codeloadAuthorization);
    }

    public void rateLimitNext(int count) {
        rateLimitNext.set(count);
    }

    public void serverErrorNext(int count) {
        serverErrorNext.set(count);
    }

    /** Redirect tarball downloads to this absolute URL instead of the fake download host (null to reset). */
    public void redirectTo(String url) {
        redirectOverride = url;
    }

    /** Permissions reported for new installation tokens (null: echo the requested ones). */
    public void grantPermissions(Map<String, String> permissions) {
        grantedPermissions = permissions;
    }

    public void reset() {
        rateLimitNext.set(0);
        serverErrorNext.set(0);
        redirectOverride = null;
        grantedPermissions = null;
    }

    private static String key(String owner, String name) {
        return (owner + "/" + name).toLowerCase(Locale.ROOT);
    }

    // --- handlers ------------------------------------------------------------------------------------------------

    private void handleApi(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (rateLimitNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
                exchange.getResponseHeaders().add("retry-after", "45");
                send(exchange, 403, Map.of("message", "rate limited"));
                return;
            }
            if (serverErrorNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(exchange, 502, Map.of("message", "bad gateway"));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String[] parts = path.split("/");
            String method = exchange.getRequestMethod();
            if (method.equals("POST") && path.equals("/app/installations/" + INSTALLATION_ID + "/access_tokens")) {
                if (!validJwt(exchange)) {
                    send(exchange, 401, Map.of("message", "bad jwt"));
                    return;
                }
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                tokenRequests.add(body.toString());
                String repo = body.path("repositories").path(0).asString("");
                String token = "ghs_" + UUID.randomUUID().toString().replace("-", "");
                tokens.put(token, repo.toLowerCase(Locale.ROOT));
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("token", token);
                response.put("permissions", grantedPermissions != null ? grantedPermissions
                        : JSON.convertValue(body.path("permissions"), Map.class));
                send(exchange, 201, response);
                return;
            }
            if (parts.length >= 4 && parts[1].equals("repos")) {
                Repo repo = repos.get(key(parts[2], parts[3]));
                if (parts.length == 5 && parts[4].equals("installation")) {
                    if (!validJwt(exchange)) {
                        send(exchange, 401, Map.of("message", "bad jwt"));
                    } else if (repo == null || !repo.installed()) {
                        send(exchange, 404, Map.of("message", "Not Found"));
                    } else {
                        send(exchange, 200, Map.of("id", INSTALLATION_ID));
                    }
                    return;
                }
                if (repo == null || !repo.installed() || !validToken(exchange, repo)) {
                    send(exchange, 404, Map.of("message", "Not Found"));
                    return;
                }
                if (parts.length == 4) {
                    send(exchange, 200, Map.of("id", repo.id(), "full_name", repo.owner() + "/" + repo.name(),
                            "default_branch", repo.defaultBranch(), "private", true));
                } else if (parts.length == 6 && parts[4].equals("branches")) {
                    send(exchange, 200, Map.of("name", repo.defaultBranch(), "commit", Map.of("sha", repo.headSha())));
                } else if (parts.length == 6 && parts[4].equals("tarball")) {
                    String location = redirectOverride != null ? redirectOverride
                            : "http://127.0.0.1:" + codeload.getAddress().getPort() + "/dl/" + repo.owner() + "/"
                            + repo.name() + "/" + parts[5] + "?signed=" + UUID.randomUUID();
                    exchange.getResponseHeaders().add("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    send(exchange, 404, Map.of("message", "Not Found"));
                }
                return;
            }
            send(exchange, 404, Map.of("message", "Not Found"));
        }
    }

    private void handleCodeload(HttpExchange exchange) throws IOException {
        try (exchange) {
            codeloadAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String[] parts = exchange.getRequestURI().getRawPath().split("/");
            Repo repo = parts.length == 5 ? repos.get(key(parts[2], parts[3])) : null;
            if (repo == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/x-gzip");
            exchange.sendResponseHeaders(200, repo.tarball().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(repo.tarball());
            }
        }
    }

    private boolean validJwt(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String[] jwt = header.substring(7).split("\\.");
        if (jwt.length != 3) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(jwt[2]))) {
                return false;
            }
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
            long now = System.currentTimeMillis() / 1000;
            return APP_ID.equals(claims.path("iss").asString("")) && claims.path("exp").asLong() > now
                    && claims.path("exp").asLong() - claims.path("iat").asLong() <= 600;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validToken(HttpExchange exchange, Repo repo) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String scope = tokens.get(header.substring(7));
        return scope != null && scope.equals(repo.name().toLowerCase(Locale.ROOT));
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // --- tarballs ------------------------------------------------------------------------------------------------

    /** A GitHub-style tarball: a pax global header with the commit SHA, then files under "owner-repo-sha/". */
    public static byte[] tarball(String commentSha, String prefix, Map<String, byte[]> files) {
        return tarball(commentSha, prefix, files, Map.of());
    }

    /** As {@link #tarball(String, String, Map)} plus raw extra entries: name -> symlink target (null: FIFO). */
    public static byte[] tarball(String commentSha, String prefix, Map<String, byte[]> files,
                                 Map<String, String> specialEntries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            if (commentSha != null) {
                // Written by the library from the entry's pax headers, as "52 comment=<sha>".
                TarArchiveEntry global = new TarArchiveEntry("pax_global_header",
                        TarConstants.LF_PAX_GLOBAL_EXTENDED_HEADER);
                global.addPaxHeader("comment", commentSha);
                tar.putArchiveEntry(global);
            }
            TarArchiveEntry dir = new TarArchiveEntry(prefix + "/");
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey().startsWith("/") || file.getKey().startsWith("..")
                        ? file.getKey() : prefix + "/" + file.getKey(), true);
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
            for (Map.Entry<String, String> special : specialEntries.entrySet()) {
                TarArchiveEntry entry = special.getValue() == null
                        ? new TarArchiveEntry(prefix + "/" + special.getKey(), TarConstants.LF_FIFO)
                        : new TarArchiveEntry(prefix + "/" + special.getKey(), TarConstants.LF_SYMLINK);
                if (special.getValue() != null) {
                    entry.setLinkName(special.getValue());
                }
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }
}
