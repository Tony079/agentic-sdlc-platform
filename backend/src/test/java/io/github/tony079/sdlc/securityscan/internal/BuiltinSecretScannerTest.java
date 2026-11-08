package io.github.tony079.sdlc.securityscan.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider;
import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider.Severity;

import org.junit.jupiter.api.Test;

/** O-03 built-in secret scanner: rules, masking, no false success, unconfigured kinds stay UNAVAILABLE. */
class BuiltinSecretScannerTest {

    private static SecurityScanProvider.Target target(Map<String, String> files, boolean broken) {
        return new SecurityScanProvider.Target() {
            @Override
            public UUID snapshotId() {
                return UUID.randomUUID();
            }

            @Override
            public String commitSha() {
                return "a".repeat(40);
            }

            @Override
            public int forEachFile(Predicate<String> paths, BiConsumer<String, byte[]> visitor) throws IOException {
                if (broken) {
                    throw new IOException("unreadable");
                }
                int n = 0;
                for (var e : files.entrySet()) {
                    if (paths.test(e.getKey())) {
                        visitor.accept(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
                        n++;
                    }
                }
                return n;
            }
        };
    }

    @Test
    void findsKnownSecretFormatsAndNeverStoresTheValue() {
        String awsKey = "AKIA" + "IOSFODNN7EXAMPLE";
        String pem = "-----BEGIN " + "RSA PRIVATE KEY-----";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/resources/application.yml", "db:\n  password: \"Sup3rS3cretValue\"\naws: " + awsKey + "\n");
        files.put("deploy/key.pem", pem + "\nMIIE...\n");
        files.put("src/App.java", "class App { String name = \"hello\"; }");
        files.put("logo.png", awsKey);
        var result = new BuiltinSecretScanner().scan(target(files, false));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.filesScanned()).isEqualTo(3);
        assertThat(result.findings()).extracting(SecurityScanProvider.Finding::ruleId)
                .containsExactlyInAnyOrder("generic-password-assignment", "aws-access-key-id", "private-key");
        var aws = result.findings().stream().filter(f -> f.ruleId().equals("aws-access-key-id")).findFirst().orElseThrow();
        assertThat(aws.severity()).isEqualTo(Severity.HIGH);
        assertThat(aws.line()).isEqualTo(3);
        assertThat(aws.evidence()).isEqualTo("AKIA…(20 chars)").doesNotContain("IOSFODNN7EXAMPLE");
        assertThat(aws.fingerprint()).hasSize(64);
        assertThat(result.findings()).allSatisfy(f -> assertThat(f.evidence()).doesNotContain("Sup3rS3cretValue"));
    }

    @Test
    void anUnreadableSnapshotIsAFailureNotASuccess() {
        assertThat(new BuiltinSecretScanner().scan(target(Map.of(), true)).status()).isEqualTo("FAILED");
        assertThat(new UnconfiguredScanProvider(SecurityScanProvider.Kind.SAST).scan(target(Map.of(), false)))
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo("UNAVAILABLE");
                    assertThat(r.errorCode()).isEqualTo("PROVIDER_NOT_CONFIGURED");
                    assertThat(r.findings()).isEmpty();
                });
    }
}
