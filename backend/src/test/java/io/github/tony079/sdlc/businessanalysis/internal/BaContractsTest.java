package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.EvidenceType;
import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

class BaContractsTest {

    @Test
    void anOverLongQuoteIsShortenedToItsStartInsteadOfRejected() {
        String line = "public class LoanController { /* repayment metadata */ } ".repeat(10);
        String json = """
                {"type": "REPOSITORY", "path": "a/b/C.java", "startLine": 1, "endLine": 9,
                 "documentVersionId": null, "chunk": null, "quote": "%s"}""".formatted(line);
        Evidence e = JsonMapper.builder().build().readValue(json, Evidence.class);

        assertThat(e.quote()).hasSizeLessThanOrEqualTo(BaContracts.QUOTE_MAX);
        assertThat(line).startsWith(e.quote());
        // Still an exact excerpt of the cited text.
        assertThat(EvidenceVerifier.contains(line, e.quote())).isTrue();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(e)).isEmpty();
        }
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        String quote = "a".repeat(BaContracts.QUOTE_MAX - 1) + "😀tail";
        Evidence e = new Evidence(EvidenceType.REPOSITORY, "p", 1, 1, null, null, quote);
        assertThat(e.quote()).isEqualTo("a".repeat(BaContracts.QUOTE_MAX - 1));
    }

    @Test
    void theTwoRulesThatFailedReq3AreAppliedByShorteningNotByRejecting() {
        String longReason = "The portfolio generator is feasible because ".repeat(40);
        String json = """
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["%s"]},
                 "items": [{"kind": "FACT", "section": "CONTEXT", "statement": "The brief lists five sections.",
                   "evidence": [{"type": "DOCUMENT", "path": null, "startLine": null, "endLine": null,
                                 "documentVersionId": "resume-brief-final-version-2026.pdf (uploaded by Robert)",
                                 "chunk": 0, "quote": "five sections"}], "risk": null, "component": null},
                  {"kind": "FACT", "section": "CONTEXT", "statement": "Second.",
                   "evidence": [{"type": "DOCUMENT", "path": null, "startLine": null, "endLine": null,
                                 "documentVersionId": "document 0f8fad5b-d9cb-469f-a165-70867728950e, chunk 2",
                                 "chunk": 2, "quote": "second"}], "risk": null, "component": null}],
                 "unsupported": [], "clarifications": []}""".formatted(longReason);
        BaContracts.Proposal p = JsonMapper.builder().build().readValue(json, BaContracts.Proposal.class);
        assertThat(p.feasibility().reasons().getFirst()).hasSizeLessThanOrEqualTo(1000).endsWith("…");
        // Not a UUID: dropped (that citation then fails verification); a UUID inside a label: kept.
        assertThat(p.items().get(0).evidence().getFirst().documentVersionId()).isNull();
        assertThat(p.items().get(0).evidence().getFirst().chunk()).isNull();
        assertThat(p.items().get(1).evidence().getFirst().documentVersionId()).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(p)).isEmpty();
        }
    }
}
