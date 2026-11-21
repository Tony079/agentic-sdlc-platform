package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.api.CodeHost;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.TrackerException;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.Reply;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GD-36: commits through GitHub's Git data API (blobs, tree on the base commit's tree, commit, branch ref) and opens a
 * pull request, so no clone, no git credentials and no working tree in the worker. Only {@code garuda/...} branches
 * are written; a default branch never is. Writes are spaced like issue writes.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class GitHubCodeHost implements CodeHost {

    static final int MAX_FILES = 300;

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final Duration pause;

    GitHubCodeHost(GitHubBroker broker, WorkerRepository repositories,
                   @Value("${sdlc.github.issue-write-pause:1s}") Duration pause) {
        this.broker = broker;
        this.repositories = repositories;
        this.pause = pause;
    }

    @Override
    public Session open(UUID repositoryId) throws TrackerException {
        RepositoryRow repo = repositories.repository(repositoryId, false).orElse(null);
        if (repo == null || !"VERIFIED".equals(repo.status()) || repo.defaultBranch() == null) {
            throw new TrackerException(TrackerException.Kind.DENIED, "REPOSITORY_NOT_VERIFIED", null);
        }
        try {
            GitHubBroker.Token token = broker.codeToken(broker.installation(repo.owner(), repo.name()), repo.name());
            return new GitHubSession(repo.owner(), repo.name(), repo.defaultBranch(), token);
        } catch (GitHubException e) {
            throw GitHubIssueTracker.translate(e);
        }
    }

    private final class GitHubSession implements Session {

        private final String owner;
        private final String name;
        private final String defaultBranch;
        private final GitHubBroker.Token token;
        private boolean wrote;

        GitHubSession(String owner, String name, String defaultBranch, GitHubBroker.Token token) {
            this.owner = owner;
            this.name = name;
            this.defaultBranch = defaultBranch;
            this.token = token;
        }

        @Override
        public String repository() {
            return owner + "/" + name;
        }

        @Override
        public String defaultBranch() {
            return defaultBranch;
        }

        @Override
        public String head() throws TrackerException {
            try {
                return broker.branchHead(owner, name, defaultBranch, token);
            } catch (GitHubException e) {
                throw GitHubIssueTracker.translate(e);
            }
        }

        @Override
        public InputStream tarball(String sha) throws TrackerException {
            try {
                return broker.tarball(owner, name, sha, token);
            } catch (GitHubException e) {
                throw GitHubIssueTracker.translate(e);
            }
        }

        @Override
        public PullRequest publish(String branch, String baseSha, List<FileChange> files, String message, String title,
                                   String body) throws TrackerException {
            if (!CodeHost.garudaBranch(branch) || branch.equals(defaultBranch)) {
                throw new IllegalArgumentException("Garuda writes only garuda/... branches");
            }
            if (!baseSha.matches("^[0-9a-f]{40}$") || files.isEmpty() || files.size() > MAX_FILES) {
                throw new TrackerException(TrackerException.Kind.ITEM_REJECTED, "CHANGE_INVALID", null);
            }
            try {
                JsonNode base = get("/git/commits/" + baseSha);
                String baseTree = base.path("tree").path("sha").asString("");
                if (!baseTree.matches("^[0-9a-f]{40}$")) {
                    throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
                }
                List<Map<String, Object>> tree = new ArrayList<>();
                for (FileChange f : files) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", f.path());
                    entry.put("mode", f.executable() ? "100755" : "100644");
                    entry.put("type", "blob");
                    if (f.content() == null) {
                        entry.put("sha", null);
                    } else {
                        JsonNode blob = write("POST", "/git/blobs", Map.of("encoding", "base64",
                                "content", Base64.getEncoder().encodeToString(f.content())), Set.of()).body();
                        entry.put("sha", sha(blob));
                    }
                    tree.add(entry);
                }
                String treeSha = sha(write("POST", "/git/trees", Map.of("base_tree", baseTree, "tree", tree),
                        Set.of()).body());
                Map<String, Object> commit = new LinkedHashMap<>();
                commit.put("message", message);
                commit.put("tree", treeSha);
                commit.put("parents", List.of(baseSha));
                String commitSha = sha(write("POST", "/git/commits", commit, Set.of()).body());
                Reply ref = write("POST", "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", commitSha),
                        Set.of(422));
                if (ref.body() == null) {
                    // The branch exists from an earlier attempt: move it to the new commit.
                    write("PATCH", "/git/refs/heads/" + branch, Map.of("sha", commitSha, "force", true), Set.of());
                }
                Reply pull = write("POST", "/pulls", Map.of("title", title, "head", branch, "base", defaultBranch,
                        "body", body), Set.of(422));
                JsonNode pr = pull.body();
                if (pr == null) {
                    Reply open = broker.call("GET", owner, name, "/pulls?state=open&head=" + owner + ":" + branch, null,
                            token, Set.of());
                    if (!open.body().isArray() || open.body().isEmpty()) {
                        throw new TrackerException(TrackerException.Kind.ITEM_REJECTED, "GITHUB_PULL_REJECTED", null);
                    }
                    pr = open.body().get(0);
                }
                int number = pr.path("number").asInt(0);
                String url = pr.path("html_url").asString("");
                if (number <= 0 || !url.matches("^https://[^\\s]{1,490}$")) {
                    throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
                }
                return new PullRequest(number, url, commitSha);
            } catch (GitHubException e) {
                throw GitHubIssueTracker.translate(e);
            }
        }

        @Override
        public String pullState(int number) throws TrackerException {
            try {
                JsonNode pr = get("/pulls/" + number);
                if (pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")) {
                    return "MERGED";
                }
                return "closed".equals(pr.path("state").asString("")) ? "CLOSED" : "OPEN";
            } catch (GitHubException e) {
                throw GitHubIssueTracker.translate(e);
            }
        }

        @Override
        public String issueState(int number) throws TrackerException {
            try {
                Reply reply = broker.call("GET", owner, name, "/issues/" + number, null, token, Set.of(301, 404, 410));
                if (reply.body() == null) {
                    return null;
                }
                return "closed".equals(reply.body().path("state").asString("")) ? "CLOSED" : "OPEN";
            } catch (GitHubException e) {
                throw GitHubIssueTracker.translate(e);
            }
        }

        private JsonNode get(String path) throws GitHubException, TrackerException {
            Reply reply = broker.call("GET", owner, name, path, null, token, Set.of());
            if (reply.body() == null || !reply.body().isObject()) {
                throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
            }
            return reply.body();
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

        private static String sha(JsonNode node) throws TrackerException {
            String sha = node == null ? "" : node.path("sha").asString("");
            if (!sha.matches("^[0-9a-f]{40}$")) {
                throw new TrackerException(TrackerException.Kind.FAILED, "GITHUB_INVALID_RESPONSE", null);
            }
            return sha;
        }
    }
}
