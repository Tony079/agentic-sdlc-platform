package io.github.tony079.sdlc.project.api;

/**
 * A user's role in one project (Garuda decisions GD-02, GD-18). Rights are cumulative: a BRD Approver has every right
 * of a Requirement Author, and a Requirement Author every right of a Viewer. The Environment Manager is not a role but
 * a member named on the project.
 */
public enum ProjectRole {
    /** Reads the project's requirements, released BRDs and status. */
    VIEWER,
    /** Also creates requirements, uploads documents and answers clarifications on their own requirements. */
    REQUIREMENT_AUTHOR,
    /** Also approves BRDs, decides change requests, authorises repositories and sees restricted requirements. */
    BRD_APPROVER;

    /** May create requirements and work on their own ones. */
    public boolean canRequest() {
        return this != VIEWER;
    }

    /** May take client-side approval decisions in the project. */
    public boolean canApprove() {
        return this == BRD_APPROVER;
    }
}
