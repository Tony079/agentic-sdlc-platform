package io.github.tony079.sdlc.identity.api;

/**
 * Staff capabilities (owner decisions C-01/O-05 and O-13). Capabilities are separate from tenant assignments and are
 * never granted automatically.
 */
public enum StaffCapability {
    /** May request or approve grants and assignments (two-person rule); gives no tenant or repository access. */
    STAFF_ADMIN,
    /** May perform final repository registration in an assigned active tenant (C-01/O-05). */
    REPOSITORY_REGISTER,
    /** R1-M6: reviews, approves, requests revision of or rejects BRD versions (TDD 19.3). */
    BUSINESS_REVIEWER,
    /** Owner decision O-25 (2026-11-08): verifies or approves a tenant soft deletion or restoration (two people). */
    TENANT_DELETE,
    /** Owner decision O-16: approves the release of an approved BRD version to the client (separate from approval). */
    RELEASE_APPROVE,
    /** Owner decision O-08: acknowledges feature-flag changes made by another operator. */
    FEATURE_FLAG_ACK,
    /** Owner decision O-27: retries a failed job of the active tenant after reviewing the failure. */
    JOB_RETRY,
    /** Owner decision C-09: assesses client change requests (scope, effort, timeline, technical impact). */
    BUSINESS_ANALYST
}
