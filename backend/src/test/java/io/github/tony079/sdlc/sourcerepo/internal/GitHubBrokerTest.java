package io.github.tony079.sdlc.sourcerepo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubException.Kind;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.FakeGitHub.Repo;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The broker against the fake GitHub (TEST ONLY: says nothing about real GitHub). */
class GitHubBrokerTest {

    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final FakeGitHub github = FakeGitHub.get();

    private GitHubBroker broker() {
        return new GitHubBroker(true, FakeGitHub.APP_ID, github.privateKeyFile().toString(), github.apiBaseUrl(),
                List.of("localhost", "127.0.0.1"), true, Duration.ofSeconds(30), JsonMapper.builder().build(),
                Clock.systemUTC());
    }

    private Repo repo(String name, boolean installed) {
        Repo repo = new Repo("broker-org", name, 9_000_001L + name.length(), "main", SHA,
                FakeGitHub.tarball(SHA, "broker-org-" + name + "-aaaaaaa", Map.of("A.java", "class A {}".getBytes())),
                installed);
        github.put(repo);
        return repo;
    }

    @AfterEach
    void reset() {
        github.reset();
    }

    @Test
    void scopedReadOnlyTokenRepositoryHeadAndTarballThroughOneRedirect() throws Exception {
        Repo repo = repo("svc-one", true);
        GitHubBroker broker = broker();
        GitHubBroker.Token token = broker.token(broker.installation(repo.owner(), repo.name()), repo.name());
        assertThat(token.toString()).doesNotContain(token.value());
        assertThat(github.tokenRequests().get(github.tokenRequests().size() - 1))
                .contains("\"repositories\":[\"svc-one\"]").contains("\"contents\":\"read\"")
                .contains("\"metadata\":\"read\"").doesNotContain("write");

        assertThat(broker.repository(repo.owner(), repo.name(), token).id()).isEqualTo(repo.id());
        assertThat(broker.branchHead(repo.owner(), repo.name(), "main", token)).isEqualTo(SHA);
        int before = github.codeloadAuthorizationHeaders().size();
        try (InputStream in = broker.tarball(repo.owner(), repo.name(), SHA, token)) {
            assertThat(in.readAllBytes()).isEqualTo(repo.tarball());
        }
        // The installation token is not forwarded to the download host.
        assertThat(github.codeloadAuthorizationHeaders().subList(before, github.codeloadAuthorizationHeaders().size()))
                .containsOnly("null");
    }

    @Test
    void appNotInstalledIsAccessDenied() {
        Repo repo = repo("not-installed", false);
        assertThatThrownBy(() -> broker().installation(repo.owner(), repo.name()))
                .isInstanceOfSatisfying(GitHubException.class, e -> assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED));
    }

    @Test
    void redirectToAHostOutsideTheAllowListIsRefused() throws Exception {
        Repo repo = repo("svc-redirect", true);
        GitHubBroker broker = broker();
        GitHubBroker.Token token = broker.token(broker.installation(repo.owner(), repo.name()), repo.name());
        github.redirectTo("http://127.0.0.2:1/evil.tar.gz");
        assertThatThrownBy(() -> broker.tarball(repo.owner(), repo.name(), SHA, token))
                .isInstanceOfSatisfying(GitHubException.class,
                        e -> assertThat(e.code()).isEqualTo("GITHUB_REDIRECT_HOST_REFUSED"));
        github.redirectTo("https://example.com/evil.tar.gz");
        assertThatThrownBy(() -> broker.tarball(repo.owner(), repo.name(), SHA, token))
                .isInstanceOfSatisfying(GitHubException.class,
                        e -> assertThat(e.code()).isEqualTo("GITHUB_REDIRECT_HOST_REFUSED"));
    }

    @Test
    void aTokenWithMoreThanReadPermissionsIsRefused() throws Exception {
        Repo repo = repo("svc-scope", true);
        GitHubBroker broker = broker();
        github.grantPermissions(Map.of("contents", "write", "metadata", "read"));
        assertThatThrownBy(() -> broker.token(broker.installation(repo.owner(), repo.name()), repo.name()))
                .isInstanceOfSatisfying(GitHubException.class, e -> assertThat(e.code()).isEqualTo("GITHUB_TOKEN_SCOPE"));
        github.grantPermissions(Map.of("contents", "read", "issues", "read"));
        assertThatThrownBy(() -> broker.token(broker.installation(repo.owner(), repo.name()), repo.name()))
                .isInstanceOfSatisfying(GitHubException.class, e -> assertThat(e.code()).isEqualTo("GITHUB_TOKEN_SCOPE"));
    }

    @Test
    void rateLimitAndServerErrorsAreClassified() {
        Repo repo = repo("svc-limits", true);
        github.rateLimitNext(1);
        assertThatThrownBy(() -> broker().installation(repo.owner(), repo.name()))
                .isInstanceOfSatisfying(GitHubException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
                    assertThat(e.delay()).isEqualTo(Duration.ofSeconds(45));
                });
        github.serverErrorNext(1);
        assertThatThrownBy(() -> broker().installation(repo.owner(), repo.name()))
                .isInstanceOfSatisfying(GitHubException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
    }

    @Test
    void urlsMustBeHttpsOnAllowedHosts() {
        GitHubBroker broker = broker();
        assertThatThrownBy(() -> broker.checkUrl(java.net.URI.create("http://api.github.com/x"), "t"))
                .hasMessageContaining("not allowed");
        GitHubBroker strict = new GitHubBroker(false, "", "", "https://api.github.com",
                List.of("api.github.com", "codeload.github.com"), false, Duration.ofSeconds(5),
                JsonMapper.builder().build(), Clock.systemUTC());
        assertThatThrownBy(() -> strict.checkUrl(java.net.URI.create("http://api.github.com/x"), "t"))
                .hasMessageContaining("HTTPS");
        strict.checkUrl(java.net.URI.create("https://codeload.github.com/x"), "t");
        assertThatThrownBy(() -> strict.installation("a", "b"))
                .isInstanceOfSatisfying(GitHubException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED));
    }

    @Test
    void privateKeyLoadsFromPkcs8AndGitHubsPkcs1Pem() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) pair.generateKeyPair().getPrivate();
        byte[] pkcs8 = key.getEncoded();
        // PKCS#1 is the PKCS#8 payload after the 26-byte header for a 2048-bit key (OCTET STRING content).
        int offset = indexOfOctetString(pkcs8);
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, offset, pkcs8.length);
        Path file = Files.createTempFile("pkcs1", ".pem");
        try {
            Files.writeString(file, "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1)
                    + "\n-----END RSA PRIVATE KEY-----\n", StandardCharsets.US_ASCII);
            assertThat(GitHubBroker.loadPrivateKey(file.toString()).getEncoded()).isEqualTo(pkcs8);
        } finally {
            Files.delete(file);
        }
        assertThatThrownBy(() -> GitHubBroker.loadPrivateKey("")).hasMessageContaining("required");
    }

    /** Offset of the RSAPrivateKey inside a PKCS#8 PrivateKeyInfo (after the OCTET STRING header). */
    private static int indexOfOctetString(byte[] der) {
        for (int i = 0; i < der.length - 4; i++) {
            if (der[i] == 0x04 && (der[i + 1] & 0xff) == 0x82 && der[i + 4] == 0x30) {
                return i + 4;
            }
        }
        throw new IllegalStateException("no octet string");
    }
}
