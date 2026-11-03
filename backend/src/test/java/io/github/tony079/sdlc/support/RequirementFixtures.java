package io.github.tony079.sdlc.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient.Response;

/** Request bodies and helpers for requirement API tests. */
public final class RequirementFixtures {

    private RequirementFixtures() {
    }

    /** A project of the logged-in user's tenant. */
    public static String project(ApiClient client) {
        Response created = client.post("/api/v1/projects", Map.of("name", "Req project " + UUID.randomUUID()));
        if (created.status() != 201) {
            throw new IllegalStateException("Project creation failed: " + created.status());
        }
        return created.body().path("id").asString();
    }

    /** All eight PRD fields, valid for submission, for an existing repository. */
    public static Map<String, Object> complete(String projectId) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("kind", "EXISTING_REPOSITORY");
        body.put("title", "Add repayment metadata " + UUID.randomUUID());
        body.put("description", "Store additional repayment metadata for each loan repayment.");
        body.put("businessObjective", "Improve reconciliation accuracy.");
        body.put("expectedOutcomes", "Repayments carry channel and reference fields.");
        body.put("acceptanceCriteria", "Metadata visible on the repayment API.");
        body.put("repositoryUrl", "https://github.com/acme-bank/loan-service");
        body.put("constraints", "None");
        body.put("integrations", "Not applicable");
        return body;
    }

    /** The minimum a draft needs: project, kind and title (decision Q2). */
    public static Map<String, Object> minimalDraft(String projectId, String kind) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("kind", kind);
        body.put("title", "Draft " + UUID.randomUUID());
        return body;
    }

    /** The content fields of an existing requirement's response, suitable for a PUT body. */
    public static Map<String, Object> contentOf(Response detail) {
        Map<String, Object> body = new HashMap<>();
        detail.body().path("content").properties().forEach(e ->
                body.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return body;
    }

    public static Map<String, String> ifMatch(Response detail) {
        return Map.of("If-Match", "\"" + detail.body().path("version").asLong() + "\"");
    }
}
