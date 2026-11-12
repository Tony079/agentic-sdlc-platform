package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.InputStream;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;

/** {@link RepositoryAccess} through the GitHub App broker: one narrowed installation token per session. */
final class GitHubRepositoryAccess implements RepositoryAccess {

    private final GitHubBroker broker;

    GitHubRepositoryAccess(GitHubBroker broker) {
        this.broker = broker;
    }

    @Override
    public String source() {
        return "github";
    }

    @Override
    public Session open(String owner, String name) throws GitHubException {
        GitHubBroker.Token token = broker.token(broker.installation(owner, name), name);
        return new Session() {
            @Override
            public RepositoryInfo repository() throws GitHubException {
                return broker.repository(owner, name, token);
            }

            @Override
            public String branchHead(String branch) throws GitHubException {
                return broker.branchHead(owner, name, branch, token);
            }

            @Override
            public InputStream tarball(String sha) throws GitHubException {
                return broker.tarball(owner, name, sha, token);
            }
        };
    }
}
