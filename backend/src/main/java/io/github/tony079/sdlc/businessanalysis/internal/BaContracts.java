package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Strict structured-output contracts of the BA agent (TDD 12.3). Unknown fields are rejected by the gateway; these
 * constraints are checked by the gateway and again by the commit step. The output is untrusted data: no field can
 * trigger an action.
 */
final class BaContracts {

    private BaContracts() {
    }

    enum Kind { FACT, ASSUMPTION, RECOMMENDATION, QUESTION }

    enum Section { FACTS, ASSUMPTIONS, RISKS, IMPACTED_COMPONENTS, MISSING_REQUIREMENTS, RECOMMENDATIONS, QUESTIONS,
        CONTEXT, CURRENT_SYSTEM, SCENARIOS, NON_FUNCTIONAL, ACCEPTANCE_CRITERIA }

    /** GD-24: the agent's feasibility verdict. NOT_FEASIBLE closes the requirement and waives its charge. */
    enum Verdict { FEASIBLE, FEASIBLE_WITH_RISKS, NOT_FEASIBLE }

    record Feasibility(@NotNull Verdict verdict, @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 1000) String> reasons) {
        Feasibility {
            reasons = Lenient.clipAll(reasons, 10, 1000);
        }
    }

    /** REQUIREMENT (v7): a field of the client's requirement, named in "path" (for example "description"). */
    enum EvidenceType { REPOSITORY, DOCUMENT, REQUIREMENT }

    /** Phase A: inventory and relevance. */
    record PhaseA(@NotBlank @Size(max = 2000) String understanding,
                  @NotNull @Size(max = 20) List<@Valid Component> components,
                  @NotNull @Size(max = 15) List<@NotBlank @Size(max = 1024) String> relevantPaths) {
        PhaseA {
            understanding = Lenient.clip(understanding, 2000);
            components = Lenient.first(components, 20);
            relevantPaths = Lenient.first(relevantPaths, 15);
        }
    }

    record Component(@NotBlank @Size(max = 200) String name,
                     @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1024) String> paths) {
        Component {
            name = Lenient.clip(name, 200);
            paths = Lenient.first(paths, 50);
        }
    }

    /** Phase B: the BRD proposal. */
    /** feasibility is optional for compatibility with results proposed before GD-24 (treated as not assessed). */
    record Proposal(@Valid Feasibility feasibility, @NotNull @Size(max = 100) List<@Valid Item> items,
                    @NotNull @Size(max = 30) List<@Valid Unsupported> unsupported,
                    @NotNull @Size(max = 10) List<@Valid ClarificationProposal> clarifications) {
        Proposal {
            items = Lenient.first(items, 60);
            unsupported = Lenient.first(unsupported, 30);
            clarifications = Lenient.first(clarifications, 10);
        }
    }

    enum Assignee { CLIENT, BUSINESS_REVIEWER }

    /** FR-CLR-01: proposed by the agent; created as a clarification only by the engine (Tx C). */
    record ClarificationProposal(@NotBlank @Size(max = 2000) String question, @Size(max = 2000) String whyItMatters,
                                 @NotNull @Size(max = 10) List<@NotBlank @Size(max = 1000) String> interpretations,
                                 @Size(max = 2000) String recommendedOption, @NotNull Boolean blocking,
                                 @NotNull Assignee assignedTo) {
        ClarificationProposal {
            question = Lenient.clip(question, 2000);
            whyItMatters = Lenient.clip(whyItMatters, 2000);
            interpretations = Lenient.clipAll(interpretations, 10, 1000);
            recommendedOption = Lenient.clip(recommendedOption, 2000);
        }
    }

    record Item(@NotNull Kind kind, @NotNull Section section, @NotBlank @Size(max = 2000) String statement,
                @NotNull @Size(max = 5) List<@Valid Evidence> evidence, @Size(max = 500) String risk,
                @Size(max = 200) String component) {
        Item {
            statement = Lenient.clip(statement, 2000);
            evidence = Lenient.first(evidence, 5);
            risk = Lenient.clip(risk, 500);
            component = Lenient.clip(component, 200);
        }
    }

    record Evidence(@NotNull EvidenceType type, @Size(max = 1024) String path, @Min(1) @Max(1_000_000) Integer startLine,
                    @Min(1) @Max(1_000_000) Integer endLine, @Size(max = 36) String documentVersionId,
                    @Min(1) @Max(5000) Integer chunk, @NotBlank @Size(max = QUOTE_MAX) String quote) {

        /**
         * A quote longer than the limit is shortened to its start instead of rejecting the whole proposal (which cost
         * a repair call). The start of an exact quote is still exact, so the citation check is unchanged.
         */
        Evidence {
            documentVersionId = Lenient.uuidOrNull(documentVersionId);
            // Out-of-range positions are dropped (that citation then fails verification) instead of failing the answer.
            startLine = Lenient.positiveOrNull(startLine, 1_000_000);
            endLine = Lenient.positiveOrNull(endLine, 1_000_000);
            chunk = Lenient.positiveOrNull(chunk, 5000);
            path = Lenient.clip(path, 1024);
            if (quote != null && quote.length() > QUOTE_MAX) {
                int end = QUOTE_MAX;
                if (Character.isHighSurrogate(quote.charAt(end - 1))) {
                    end--;
                }
                quote = quote.substring(0, end).stripTrailing();
            }
        }
    }

    static final int QUOTE_MAX = 300;

    record Unsupported(@NotBlank @Size(max = 1000) String requirement) {
        Unsupported {
            requirement = Lenient.clip(requirement, 1000);
        }
    }
}
