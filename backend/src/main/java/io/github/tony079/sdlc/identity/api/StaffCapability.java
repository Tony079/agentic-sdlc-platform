package io.github.tony079.sdlc.identity.api;

/**
 * Staff capabilities (owner decisions C-01/O-05 and O-13). Capabilities are separate from tenant assignments and are
 * never granted automatically.
 */
public enum StaffCapability {
    /** May request or approve grants and assignments (two-person rule); gives no tenant or repository access. */
    STAFF_ADMIN,
    /** May perform final repository registration in an assigned active tenant (C-01/O-05). */
    REPOSITORY_REGISTER
}
