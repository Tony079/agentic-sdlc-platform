package io.github.tony079.sdlc.sourcerepo.api;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.TrackerException;

/**
 * GD-36 (worker only): what the Developer agent needs from GitHub, through the Garuda GitHub App. The token is narrowed
 * to the one repository with Contents: write, Pull requests: write, Issues: read and Metadata: read; it never leaves
 * the worker. Garuda writes only branches named {@code garuda/<requirement>/<story>} and never merges.
 */
public interface CodeHost {

    /** Opens a session on a verified repository of the current tenant. Requires an active transaction. */
    Session open(UUID repositoryId) throws TrackerException;

    interface Session {

        String repository();

        String defaultBranch();

        /** The commit SHA at the head of the default branch. */
        String head() throws TrackerException;

        /** The repository tarball at a commit (GitHub's format). The caller closes the stream. */
        InputStream tarball(String sha) throws TrackerException;

        /**
         * Commits the files on top of {@code baseSha} to the branch (created, or moved when it exists from an
         * earlier attempt) and opens a pull request against the default branch, or updates the open one (title and
         * body). A draft pull request holds unfinished work; publishing the same branch with {@code draft} false
         * marks it ready for review.
         */
        PullRequest publish(String branch, String baseSha, List<FileChange> files, String message, String title,
                            String body, boolean draft) throws TrackerException;

        /** The commit SHA at the head of a garuda/ branch; null when the branch does not exist. */
        String branchHead(String branch) throws TrackerException;

        /** A pull request's state: OPEN, MERGED or CLOSED. */
        String pullState(int number) throws TrackerException;

        /** An issue's state: OPEN or CLOSED; null when it no longer exists. */
        String issueState(int number) throws TrackerException;
    }

    /** A file to write (content set) or delete (content null). */
    record FileChange(String path, byte[] content, boolean executable) {
    }

    record PullRequest(int number, String url, String headSha) {
    }

    /** Branches Garuda may write. */
    static boolean garudaBranch(String branch) {
        return branch != null && branch.matches("^garuda/[a-z0-9-]{1,40}/[a-z0-9-]{1,80}$");
    }
}
