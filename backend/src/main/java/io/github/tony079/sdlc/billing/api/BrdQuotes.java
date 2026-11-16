package io.github.tony079.sdlc.billing.api;

import java.util.UUID;

/**
 * GD-22: per-BRD charging. A project with a billing plan starts a requirement's analysis only after its BRD quote is
 * accepted; projects without a plan (in use before billing) are not quoted.
 */
public interface BrdQuotes {

    /** What the size of a BRD is quoted from: documents, repositories analysed (GD-23) and requirement text. */
    record Basis(int documents, int repositories, int textChars) {
    }

    /**
     * API, in the submitting transaction: opens the requirement's quote when its project has a billing plan (a
     * resubmission keeps the existing quote).
     */
    void open(UUID requirementId, UUID projectId, Basis basis);

    /**
     * Worker, in the requirement's tenant transaction: true when analysis may start (no quote, i.e. a project without
     * a plan, or an accepted quote). A proposed quote is refreshed from the current inputs.
     */
    boolean readyForAnalysis(UUID requirementId, Basis basis);

    /** Worker (GD-24): waives the accepted quote of a requirement closed as not feasible (no charge, allowance freed). */
    void waive(UUID requirementId, String reason);
}
