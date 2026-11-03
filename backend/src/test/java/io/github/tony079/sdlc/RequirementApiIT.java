package io.github.tony079.sdlc;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.contentOf;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.minimalDraft;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;

import org.junit.jupiter.api.Test;

/** Requirement creation and management through the HTTP API (PRD FR-REQ-01/03/04/06; decisions Q1, Q2, Q4). */
class RequirementApiIT extends ApiIntegrationTest {

    @Test
    void createListViewUpdateAndSubmit() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String projectId = project(alice);

        Response created = alice.post("/api/v1/requirements", complete(projectId));
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(created.body().path("key").asString()).matches("REQ-\\d{6}");
        assertThat(created.body().path("state").asString()).isEqualTo("DRAFT");
        assertThat(created.body().path("currentRevisionNo").asInt()).isEqualTo(1);
        assertThat(created.header("ETag")).isEqualTo("\"0\"");
        assertThat(created.header("Location")).isEqualTo("/api/v1/requirements/" + id);

        Response list = alice.get("/api/v1/requirements");
        assertThat(list.body().path("items").valueStream().map(n -> n.path("id").asString())).contains(id);

        Response view = alice.get("/api/v1/requirements/" + id);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().path("content").path("constraints").asString()).isEqualTo("None");

        Map<String, Object> edited = contentOf(view);
        edited.put("title", "Edited title");
        Response updated = alice.put("/api/v1/requirements/" + id, edited, ifMatch(view));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("currentRevisionNo").asInt()).isEqualTo(2);
        assertThat(updated.body().path("version").asLong()).isEqualTo(1);

        Response submitted = alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(updated), true);
        assertThat(submitted.status()).isEqualTo(200);
        assertThat(submitted.body().path("state").asString()).isEqualTo("SUBMITTED");

        // Decision Q4: submission performs only DRAFT -> SUBMITTED; no routing in Milestone 2.
        Response timeline = alice.get("/api/v1/requirements/" + id + "/timeline");
        assertThat(timeline.body().valueStream().map(n -> n.path("toState").asString()))
                .containsExactly("DRAFT", "SUBMITTED");
    }

    @Test
    void partialDraftIsAcceptedAndSubmitReportsEachMissingField() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response draft = alice.post("/api/v1/requirements", minimalDraft(project(alice), "EXISTING_REPOSITORY"));
        assertThat(draft.status()).isEqualTo(201);

        Response submit = alice.post("/api/v1/requirements/" + draft.body().path("id").asString() + "/submit",
                null, ifMatch(draft), true);
        assertThat(submit.status()).isEqualTo(422);
        assertThat(submit.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(submit.fieldErrors()).containsExactlyInAnyOrder("description:REQUIRED",
                "businessObjective:REQUIRED", "expectedOutcomes:REQUIRED", "constraints:REQUIRED",
                "integrations:REQUIRED", "repositoryUrl:REQUIRED");
        assertThat(alice.get("/api/v1/requirements/" + draft.body().path("id").asString())
                .body().path("state").asString()).isEqualTo("DRAFT");
    }

    @Test
    void draftNeedsProjectKindAndTitle() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response response = alice.post("/api/v1/requirements", Map.of("description", "no essentials"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.fieldErrors()).containsExactlyInAnyOrder("projectId:REQUIRED", "kind:REQUIRED",
                "title:REQUIRED");
    }

    @Test
    void newProductMustNotCarryARepositoryUrlAndIsSubmittableWithoutOne() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Map<String, Object> body = complete(project(alice));
        body.put("kind", "NEW_PRODUCT");
        Response rejected = alice.post("/api/v1/requirements", body);
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.fieldErrors()).containsExactly("repositoryUrl:MUST_BE_ABSENT");

        body.remove("repositoryUrl");
        Response created = alice.post("/api/v1/requirements", body);
        Response submitted = alice.post("/api/v1/requirements/" + created.body().path("id").asString() + "/submit",
                null, ifMatch(created), true);
        assertThat(submitted.status()).isEqualTo(200);
        assertThat(submitted.body().path("state").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    void repositoryUrlIsValidatedSyntacticallyOnly() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Map<String, Object> body = complete(project(alice));
        body.put("repositoryUrl", "http://github.com/acme/app");
        assertThat(alice.post("/api/v1/requirements", body).fieldErrors()).containsExactly("repositoryUrl:INVALID_FORMAT");
        body.put("repositoryUrl", "https://github.com/acme/" + "x".repeat(290));
        assertThat(alice.post("/api/v1/requirements", body).fieldErrors()).containsExactly("repositoryUrl:TOO_LONG");
        // Syntactically valid but certainly non-existent: accepted, no GitHub call (repository milestone).
        body.put("repositoryUrl", "https://github.com/no-such-owner-" + UUID.randomUUID().toString().substring(0, 8) + "/x");
        assertThat(alice.post("/api/v1/requirements", body).status()).isEqualTo(201);
    }

    @Test
    void fieldLengthLimitsAreEnforced() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Map<String, Object> body = complete(project(alice));
        body.put("businessObjective", "b".repeat(5_001));
        body.put("title", "t".repeat(201));
        assertThat(alice.post("/api/v1/requirements", body).fieldErrors())
                .containsExactlyInAnyOrder("businessObjective:TOO_LONG", "title:TOO_LONG");
    }

    @Test
    void unknownFieldsAndForeignProjectsAreRejected() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Map<String, Object> body = complete(project(alice));
        body.put("tenantId", UUID.randomUUID().toString());
        assertThat(alice.post("/api/v1/requirements", body).status()).isEqualTo(422);

        ApiClient gina = loggedIn("globex", "gina@globex.test");
        Response foreignProject = gina.post("/api/v1/requirements", complete(project(alice)));
        assertThat(foreignProject.status()).isEqualTo(422);
        assertThat(foreignProject.fieldErrors()).containsExactly("projectId:INVALID_VALUE");
    }

    @Test
    void mutationsNeedIfMatchAndAStaleVersionConflicts() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();

        Response noPrecondition = alice.put("/api/v1/requirements/" + id, contentOf(created), Map.of());
        assertThat(noPrecondition.status()).isEqualTo(428);
        assertThat(noPrecondition.errorCode()).isEqualTo("PRECONDITION_REQUIRED");

        assertThat(alice.put("/api/v1/requirements/" + id, contentOf(created), ifMatch(created)).status()).isEqualTo(200);
        Response stale = alice.put("/api/v1/requirements/" + id, contentOf(created), ifMatch(created));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.errorCode()).isEqualTo("VERSION_CHANGED");
        Response staleSubmit = alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true);
        assertThat(staleSubmit.errorCode()).isEqualTo("VERSION_CHANGED");
    }

    @Test
    void cancelNeedsAReasonAndCancelledIsTerminal() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();

        Response noReason = alice.post("/api/v1/requirements/" + id + "/cancel", Map.of("reason", "short"),
                ifMatch(created), true);
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.fieldErrors()).containsExactly("reason:INVALID_LENGTH");

        Response cancelled = alice.post("/api/v1/requirements/" + id + "/cancel",
                Map.of("reason", "No longer needed by the business"), ifMatch(created), true);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");

        Response edit = alice.put("/api/v1/requirements/" + id, contentOf(cancelled), ifMatch(cancelled));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.errorCode()).isEqualTo("REQUIREMENT_NOT_EDITABLE");

        Response submit = alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(cancelled), true);
        assertThat(submit.status()).isEqualTo(409);
        assertThat(submit.errorCode()).isEqualTo("ILLEGAL_TRANSITION");
        // Nothing is deleted: the requirement and its history remain readable.
        assertThat(alice.get("/api/v1/requirements/" + id + "/timeline").body().size()).isEqualTo(2);
    }

    @Test
    void submittedRequirementCanStillBeRevised() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();
        Response submitted = alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true);
        Map<String, Object> edit = contentOf(submitted);
        edit.put("expectedOutcomes", "Revised after submission");
        Response revised = alice.put("/api/v1/requirements/" + id, edit, ifMatch(submitted));
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().path("state").asString()).isEqualTo("SUBMITTED");
        assertThat(revised.body().path("currentRevisionNo").asInt()).isEqualTo(2);
    }

    @Test
    void listFiltersByStateAndPaginates() {
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String projectId = project(aaron);
        for (int i = 0; i < 3; i++) {
            aaron.post("/api/v1/requirements", minimalDraft(projectId, "NEW_PRODUCT"));
        }
        Response first = aaron.get("/api/v1/requirements?projectId=" + projectId + "&state=DRAFT&limit=2");
        assertThat(first.body().path("items").size()).isEqualTo(2);
        String cursor = first.body().path("nextCursor").asString();
        Response second = aaron.get("/api/v1/requirements?projectId=" + projectId + "&state=DRAFT&limit=2&cursor=" + cursor);
        assertThat(second.body().path("items").size()).isEqualTo(1);
        assertThat(second.body().path("nextCursor").isNull()).isTrue();
        assertThat(aaron.get("/api/v1/requirements?projectId=" + projectId + "&state=SUBMITTED").body()
                .path("items").size()).isZero();
        assertThat(aaron.get("/api/v1/requirements?limit=0").status()).isEqualTo(422);
    }
}
