package io.github.tony079.sdlc.sourcerepo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.Inventory;

import org.junit.jupiter.api.Test;

/** The development repository adapter serves one bundled sample, in the format the real snapshot reader checks. */
class DevelopmentRepositoryAccessTest {

    private final DevelopmentRepositoryAccess access = new DevelopmentRepositoryAccess();

    @Test
    void servesTheBundledSampleInGitHubTarballFormat() throws Exception {
        RepositoryAccess.Session session = access.open("sdlc-dev", "sample-orders");
        RepositoryInfo info = session.repository();
        assertThat(info.fullName()).isEqualTo("sdlc-dev/sample-orders");
        assertThat(info.id()).isPositive().isEqualTo(access.open("SDLC-DEV", "sample-orders").repository().id());
        assertThat(access.open("sdlc-dev", "other").repository().id()).isNotEqualTo(info.id());

        String sha = session.branchHead(info.defaultBranch());
        assertThat(sha).matches("[0-9a-f]{40}");
        byte[] archive;
        try (InputStream in = session.tarball(sha)) {
            archive = in.readAllBytes();
        }
        // The real reader accepts it: SHA comment, limits, paths.
        Inventory inventory = SnapshotArchive.read(new java.io.ByteArrayInputStream(archive), archive.length, sha, null);
        assertThat(inventory.files().stream().map(SnapshotArchive.FileEntry::path))
                .contains("pom.xml", "src/main/java/com/example/orders/OrderService.java");
    }

    @Test
    void refusesEverythingElse() throws Exception {
        assertThatThrownBy(() -> access.open("acme-bank", "loan-service")).isInstanceOf(GitHubException.class)
                .satisfies(e -> assertThat(((GitHubException) e).kind()).isEqualTo(GitHubException.Kind.ACCESS_DENIED));
        RepositoryAccess.Session session = access.open("sdlc-dev", "sample-orders");
        assertThatThrownBy(() -> session.branchHead("feature")).isInstanceOf(GitHubException.class);
        assertThatThrownBy(() -> session.tarball("0".repeat(40))).isInstanceOf(GitHubException.class);
    }
}
