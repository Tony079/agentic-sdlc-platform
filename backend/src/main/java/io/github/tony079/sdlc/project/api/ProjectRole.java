package io.github.tony079.sdlc.project.api;

/**
 * A user's role in one project (Garuda decision GD-02). Rights are cumulative: an APPROVER has every right of a
 * REQUESTER, and a REQUESTER every right of a VIEWER.
 */
public enum ProjectRole {
    /** Reads the project's requirements, released BRDs and status. */
    VIEWER,
    /** Also creates and works on their own requirements. */
    REQUESTER,
    /** Also authorises repositories, decides change requests and sees restricted requirements. */
    APPROVER;

    /** May create requirements and work on their own ones. */
    public boolean canRequest() {
        return this != VIEWER;
    }

    /** May take client-side approval decisions in the project. */
    public boolean canApprove() {
        return this == APPROVER;
    }
}
