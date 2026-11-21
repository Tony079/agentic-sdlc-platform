package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.api.IssueTracker;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.Reply;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GD-34 phase 2: GitHub Issues through the broker. The token asks for Issues: Read and write and Metadata: Read on the
 * one repository only. Writes are spaced by {@code sdlc.github.issue-write-pause} (GitHub asks integrations to pause
 * between content-creating requests). An item deleted or transferred on GitHub since the last push is created again.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class GitHubIssueTracker implements IssueTracker {

    private static final Set<Integer> GONE = Set.of(301, 404, 410);
    private static final int MAX_MILESTONE_PAGES = 5;

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final Duration pause;

    GitHubIssueTracker(GitHubBroker broker, WorkerRepository repositories,
                       @Value("${sdlc.github.issue-write-pause:1s}") Duration pause) {
        this.broker = broker;
        this.repositories = repositories;
        this.pause = pause;
    }

    @Override
    public Session open(UUID repositoryId) throws TrackerException {
        RepositoryRow repo = repositories.repository(repositoryId, false).orElse(null);
        if (repo == null || !"VERIFIED".equals(repo.status())) {
            throw new TrackerException(TrackerException.Kind.DENIED, "REPOSITORY_NOT_VERIFIED", null);
        }
        try {
            GitHubBroker.Token token = broker.issuesToken(broker.installation(repo.owner(), repo.name()), repo.name());
            return new GitHubSession(repo.owner(), repo.name(), token);
        } catch (GitHubException e) {
            throw translate(e);
        }
    }

    static TrackerException translate(GitHubException e) {
        return switch (e.kind()) {
            case RATE_LIMITED -> new TrackerException(TrackerException.Kind.RETRY, e.code(), e.delay());
            case TRANSIENT -> new TrackerException(TrackerException.Kind.RETRY, e.code(), null);
            case NOT_CONFIGURED -> new TrackerException(TrackerException.Kind.NOT_CONFIGURED, e.code(), null);
            case ACCESS_DENIED, UNAVAILABLE -> new TrackerException(TrackerException.Kind.DENIED, e.code(), null);
            case INVALID_RESPONSE -> new TrackerException(TrackerException.Kind.FAILED, e.code(), null);
        };
    }

    private final class GitHubSession implements Session {

        private final String owner;
        private final String name;
        private final GitHubBroker.Token token;
        private boolean wrote;

        GitHubSession(String owner, String name, GitHubBroker.Token token) {
            this.owner = owner;
            this.name = name;
            this.token = token;
        }

        @Override
        public String repository() {
            return owner + "/" + name;
        }

        @Override
        public Ref milestone(Integer existing, String title, String description, LocalDate dueOn)
                throws TrackerException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", title);
            body.put("description", description);
            body.put("due_on", dueOn + "T23:59:59Z");
            try {
                if (existing != null) {
                    Reply updated = write("PATCH", "/milestones/" + existing, body, GONE);
                    if (updated.body() != null) {
                        return ref(updated.body(), false);
                    }
                }
                Reply created = write("POST", "/milestones", body, Set.of(422));
                if (created.body() != null) {
                    return ref(created.body(), true);
                }
                // A milestone with this title already exists (created by hand or by an earlier push): reuse it.
                Integer found = milestoneByTitle(title);
                if (found == null) {
                    throw new TrackerException(TrackerException.Kind.ITEM_REJECTED, "GITHUB_MILESTONE_REJECTED", null);
                }
                Reply reused = write("PATCH", "/milestones/" + found, body, Set.of());
                return ref(reused.body(), false);
            } catch (GitHubException e) {
                throw translate(e);
            }
        }

        private Integer milestoneByTitle(String title) throws GitHubException, TrackerException {
            for (int page = 1; page <= MAX_MILESTONE_PAGES; page++) {
                Reply reply = broker.call("GET", owner, name, "/milestones?state=all&per_page=100&page=" + page, null,
                        token, Set.of());
                if (!reply.body().isArray()) {
                    throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
                }
                for (JsonNode m : reply.body()) {
                    if (title.equals(m.path("title").asString(""))) {
                        int number = m.path("number").asInt(0);
                        return number > 0 ? number : null;
                    }
                }
                if (reply.body().size() < 100) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public void label(String label, String color, String description) throws TrackerException {
            try {
                // 422: the label exists already, which is fine.
                write("POST", "/labels", Map.of("name", label, "color", color, "description", description),
                        Set.of(422));
            } catch (GitHubException e) {
                throw translate(e);
            }
        }

        @Override
        public String body(int number) throws TrackerException {
            try {
                Reply reply = broker.call("GET", owner, name, "/issues/" + number, null, token, GONE);
                if (reply.body() == null || reply.body().has("pull_request")) {
                    return null;
                }
                return reply.body().path("body").asString("");
            } catch (GitHubException e) {
                throw translate(e);
            }
        }

        @Override
        public Ref issue(Integer existing, String title, String body, List<String> labels, Integer milestone)
                throws TrackerException {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("title", title);
            request.put("body", body);
            request.put("labels", labels);
            request.put("milestone", milestone);
            try {
                if (existing != null) {
                    Reply updated = write("PATCH", "/issues/" + existing, request, Set.of(301, 404, 410, 422));
                    if (updated.body() != null) {
                        return ref(updated.body(), false);
                    }
                    if (updated.status() == 422) {
                        throw new TrackerException(TrackerException.Kind.ITEM_REJECTED, "GITHUB_ISSUE_REJECTED", null);
                    }
                }
                Reply created = write("POST", "/issues", request, Set.of(410, 422));
                if (created.status() == 410) {
                    throw new TrackerException(TrackerException.Kind.DENIED, "GITHUB_ISSUES_DISABLED", null);
                }
                if (created.body() == null) {
                    throw new TrackerException(TrackerException.Kind.ITEM_REJECTED, "GITHUB_ISSUE_REJECTED", null);
                }
                return ref(created.body(), true);
            } catch (GitHubException e) {
                throw translate(e);
            }
        }

        private Reply write(String method, String path, Object body, Set<Integer> handled) throws GitHubException {
            if (wrote && !pause.isZero()) {
                try {
                    Thread.sleep(pause.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GitHubException(GitHubException.Kind.TRANSIENT, "GITHUB_INTERRUPTED");
                }
            }
            wrote = true;
            return broker.call(method, owner, name, path, body, token, handled);
        }

        private static Ref ref(JsonNode node, boolean created) throws TrackerException {
            int number = node.path("number").asInt(0);
            String url = node.path("html_url").asString("");
            if (number <= 0 || !url.matches("^https://[^\\s]{1,490}$")) {
                throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
            }
            return new Ref(number, url, created);
        }
    }
}
