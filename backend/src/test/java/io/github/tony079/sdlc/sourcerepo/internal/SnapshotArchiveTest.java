package io.github.tony079.sdlc.sourcerepo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.FileEntry;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.Inventory;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.Limits;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.SnapshotRejected;
import io.github.tony079.sdlc.support.FakeGitHub;

import org.junit.jupiter.api.Test;

/** TDD 14.5 limits and path safety of the streamed snapshot reader. */
class SnapshotArchiveTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PREFIX = "acme-loans-0123456";

    private static Inventory read(byte[] tarball, String sha, Limits limits, List<String> visited) throws Exception {
        return SnapshotArchive.read(new ByteArrayInputStream(tarball), tarball.length, sha,
                (path, language, content) -> visited.add(path + ":" + language + ":" + content.length), limits);
    }

    @Test
    void inventoryListsFilesWithLanguageSizeHashAndIndexability() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pom.xml", "<project/>".getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/a/App.java", "class App {}".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", "# readme".getBytes(StandardCharsets.UTF_8));
        files.put("big/Huge.java", new byte[(int) SnapshotArchive.MAX_INDEXED_FILE_BYTES + 1]);
        List<String> visited = new ArrayList<>();
        Map<String, String> special = new HashMap<>();
        special.put("link-to-etc", "/etc/passwd");
        special.put("pipe", null);
        Inventory inventory = read(FakeGitHub.tarball(SHA, PREFIX, files, special), SHA,
                new Limits(100, 1L << 30, 100_000), visited);

        Map<String, FileEntry> byPath = new HashMap<>();
        inventory.files().forEach(f -> byPath.put(f.path(), f));
        assertThat(byPath).containsKeys("pom.xml", "src/main/java/a/App.java", "README.md", "big/Huge.java",
                "link-to-etc", "pipe");
        assertThat(byPath.get("src/main/java/a/App.java")).satisfies(f -> {
            assertThat(f.language()).isEqualTo("JAVA");
            assertThat(f.indexable()).isTrue();
            assertThat(f.size()).isEqualTo(12);
            assertThat(f.sha256()).matches("^[0-9a-f]{64}$");
        });
        assertThat(byPath.get("README.md").skipReason()).isEqualTo("NOT_INDEXED_TYPE");
        assertThat(byPath.get("big/Huge.java").skipReason()).isEqualTo("TOO_LARGE");
        assertThat(byPath.get("link-to-etc").skipReason()).isEqualTo("LINK");
        assertThat(byPath.get("pipe").skipReason()).isEqualTo("SPECIAL_FILE");
        assertThat(visited).containsExactlyInAnyOrder("pom.xml:MAVEN:10", "src/main/java/a/App.java:JAVA:12");
    }

    @Test
    void theCommitShaInThePaxHeaderMustMatchThePinnedSha() {
        byte[] tarball = FakeGitHub.tarball(SHA, PREFIX, Map.of("a.txt", new byte[1]));
        assertThatThrownBy(() -> read(tarball, "f".repeat(40), Limits.DEFAULT, new ArrayList<>()))
                .isInstanceOf(SnapshotRejected.class).hasMessage("SNAPSHOT_SHA_MISMATCH");
        byte[] noHeader = FakeGitHub.tarball(null, PREFIX, Map.of("a.txt", new byte[1]));
        assertThatThrownBy(() -> read(noHeader, SHA, Limits.DEFAULT, new ArrayList<>()))
                .isInstanceOf(SnapshotRejected.class).hasMessage("SNAPSHOT_SHA_MISMATCH");
    }

    @Test
    void unsafePathsAreRejectedFromTheInventory() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ok.txt", new byte[1]);
        files.put("../escape.txt", new byte[1]);
        files.put("/etc/abs.txt", new byte[1]);
        files.put("a/../../up.txt", new byte[1]);
        Inventory inventory = read(FakeGitHub.tarball(SHA, PREFIX, files), SHA, Limits.DEFAULT, new ArrayList<>());
        assertThat(inventory.files()).extracting(FileEntry::path).containsExactly("ok.txt");
        assertThat(inventory.rejectedCount()).isEqualTo(3);

        assertThat(SnapshotArchive.relativePath("p/a\u0000b")).isNull();
        assertThat(SnapshotArchive.relativePath("p/a\\b")).isNull();
        assertThat(SnapshotArchive.relativePath("p/./b")).isNull();
        assertThat(SnapshotArchive.relativePath("p/" + "x".repeat(1100))).isNull();
        assertThat(SnapshotArchive.relativePath("p/src/A.java")).isEqualTo("src/A.java");
    }

    @Test
    void entryCountExpandedSizeAndCompressionRatioAreLimited() {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            many.put("f" + i + ".txt", new byte[1]);
        }
        assertThatThrownBy(() -> read(FakeGitHub.tarball(SHA, PREFIX, many), SHA, new Limits(10, 1L << 30, 1000),
                new ArrayList<>())).hasMessage("SNAPSHOT_TOO_MANY_FILES");

        byte[] zeros = FakeGitHub.tarball(SHA, PREFIX, Map.of("zeros.bin", new byte[4_000_000]));
        assertThatThrownBy(() -> read(zeros, SHA, Limits.DEFAULT, new ArrayList<>()))
                .hasMessage("SNAPSHOT_RATIO_EXCEEDED");
        assertThatThrownBy(() -> read(zeros, SHA, new Limits(100, 1_000_000, 1_000_000), new ArrayList<>()))
                .hasMessage("SNAPSHOT_EXPANDED_TOO_LARGE");
    }

    @Test
    void corruptArchivesAreRejected() {
        assertThatThrownBy(() -> read("not a gzip".getBytes(StandardCharsets.UTF_8), SHA, Limits.DEFAULT,
                new ArrayList<>())).isInstanceOf(SnapshotRejected.class).hasMessage("SNAPSHOT_CORRUPT");
    }
}
