package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.InputStream;

import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;

/**
 * Read-only access to a registered repository for one job (TDD 14.2). The repository jobs depend on this port only.
 * Two adapters exist: {@link GitHubRepositoryAccess} (the GitHub App broker, the default) and
 * {@link DevelopmentRepositoryAccess} (a bundled sample repository for local prototypes, never for production).
 * Which one runs is server configuration ({@code sdlc.repository.source}), never a request value.
 */
interface RepositoryAccess {

    /** The adapter's name, for logs and the runtime information endpoint. */
    String source();

    /** Opens access to one repository; ACCESS_DENIED when the platform has no access to it. */
    Session open(String owner, String name) throws GitHubException;

    /** Access to one repository with one credential, valid for one job. */
    interface Session {

        /** Trusted repository metadata: stable numeric id, full name and default branch. */
        RepositoryInfo repository() throws GitHubException;

        /** The commit SHA at the head of a branch. */
        String branchHead(String branch) throws GitHubException;

        /** The tarball of a commit, in GitHub's format (pax global header carrying the SHA). The caller closes it. */
        InputStream tarball(String sha) throws GitHubException;
    }
}
