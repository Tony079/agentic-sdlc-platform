package io.github.tony079.sdlc.sourcerepo.api;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GD-34 phase 2 (worker only): writes issues, milestones and labels to a verified GitHub repository of the current
 * tenant through the GitHub App. The installation token is narrowed to that one repository with Issues: Read and write
 * and Metadata: Read; it is never stored, logged or returned. Text passed in is sent as is: callers neutralise
 * mentions and references in untrusted text first.
 */
public interface IssueTracker {

    /** Opens a write session on the repository. Requires an active transaction in the repository's tenant. */
    Session open(UUID repositoryId) throws TrackerException;

    /** One repository, one token. Not thread-safe. */
    interface Session {

        /** "owner/name". */
        String repository();

        /** Updates the milestone when {@code existing} still exists, otherwise finds it by title or creates it. */
        Ref milestone(Integer existing, String title, String description, LocalDate dueOn) throws TrackerException;

        /** Creates the label unless it exists. */
        void label(String name, String color, String description) throws TrackerException;

        /** The issue's current description, or null when it no longer exists in the repository. */
        String body(int number) throws TrackerException;

        /** Updates the issue when {@code existing} still exists in the repository, otherwise creates it. */
        Ref issue(Integer existing, String title, String body, List<String> labels, Integer milestone)
                throws TrackerException;
    }

    /** What GitHub created or updated: its number, browser URL and whether it was new. */
    record Ref(int number, String url, boolean created) {
    }

    /** A classified failure; the code is content-free. */
    final class TrackerException extends Exception {

        public enum Kind {
            /** The App is not installed, lacks Issues permission, or the repository is gone: not retried. */
            DENIED,
            /** Rate limited or GitHub unavailable: retried later. */
            RETRY,
            /** The integration is not configured on this worker: retried later. */
            NOT_CONFIGURED,
            /** GitHub refused this one item (validation): the push continues with the next item. */
            ITEM_REJECTED,
            /** Anything else: not retried. */
            FAILED
        }

        private final Kind kind;
        private final String code;
        private final Duration delay;

        public TrackerException(Kind kind, String code, Duration delay) {
            super(code);
            this.kind = kind;
            this.code = code;
            this.delay = delay;
        }

        public Kind kind() {
            return kind;
        }

        public String code() {
            return code;
        }

        public Duration delay() {
            return delay;
        }
    }
}
