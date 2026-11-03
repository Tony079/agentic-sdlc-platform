package io.github.tony079.sdlc;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.contentOf;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** Every edit is a new immutable revision (PRD FR-REQ-04); earlier revisions never change. */
class RequirementRevisionIT extends ApiIntegrationTest {

    @Test
    void editsAppendRevisionsAndEarlierRevisionsStayUnchanged() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response v1 = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = v1.body().path("id").asString();
        String originalTitle = v1.body().path("content").path("title").asString();
        String hash1 = alice.get("/api/v1/requirements/" + id + "/revisions/1").body().path("contentHash").asString();

        Map<String, Object> edit = contentOf(v1);
        edit.put("title", "Second title");
        Response v2 = alice.put("/api/v1/requirements/" + id, edit, ifMatch(v1));
        edit.put("title", "Third title");
        Response v3 = alice.put("/api/v1/requirements/" + id, edit, ifMatch(v2));
        assertThat(v3.body().path("currentRevisionNo").asInt()).isEqualTo(3);
        assertThat(v3.body().path("revisions").size()).isEqualTo(3);

        Response first = alice.get("/api/v1/requirements/" + id + "/revisions/1");
        assertThat(first.body().path("content").path("title").asString()).isEqualTo(originalTitle);
        assertThat(first.body().path("contentHash").asString()).isEqualTo(hash1);
        assertThat(alice.get("/api/v1/requirements/" + id + "/revisions/2").body().path("content").path("title")
                .asString()).isEqualTo("Second title");
        assertThat(alice.get("/api/v1/requirements/" + id + "/revisions/9").status()).isEqualTo(404);

        List<String> hashes = TestDatabase.get().admin().queryForList(
                "SELECT content_hash FROM app.requirement_revision WHERE requirement_id = ? ORDER BY revision_no",
                String.class, UUID.fromString(id));
        assertThat(hashes).hasSize(3).doesNotHaveDuplicates();
        assertThat(hashes.get(0)).isEqualTo(hash1);
    }

    @Test
    void runtimeRoleCannotRewriteOrDeleteRevisions() throws Exception {
        try (Connection app = TestDatabase.get().appConnection(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("UPDATE app.requirement_revision SET title = 'rewritten'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM app.requirement_revision"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM app.requirement"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("UPDATE app.requirement SET key = 'REQ-999999'"))
                    .hasMessageContaining("permission denied");
        }
    }
}
