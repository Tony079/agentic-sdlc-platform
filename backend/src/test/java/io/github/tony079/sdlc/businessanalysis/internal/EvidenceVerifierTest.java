package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.EvidenceType;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Item;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Kind;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Proposal;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Section;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Unsupported;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.Chunk;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.DocumentSource;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedItem;

import org.junit.jupiter.api.Test;

/** TDD 16.3 evidence checks (EV-01..08 style): forged, out-of-range, cross-version and unsupported citations. */
class EvidenceVerifierTest {

    private static final String PATH = "src/main/java/com/acme/LoanController.java";
    private static final String CODE = """
            package com.acme;
            @RestController
            @RequestMapping("/loans")
            class LoanController {
                @GetMapping("/{id}")
                Loan get(long id) { return repository.find(id); }
            }
            """;
    private static final UUID DOC = UUID.randomUUID();
    private static final Map<UUID, DocumentSource> DOCS = Map.of(DOC, new DocumentSource(DOC, 1,
            Map.of(1, new Chunk(1, "Section 2", "Repayments must carry a channel code and a reference."))));

    private static Item fact(String statement, Evidence... evidence) {
        return new Item(Kind.FACT, Section.FACTS, statement, List.of(evidence), null, null);
    }

    private static Evidence repo(String path, int start, int end, String quote) {
        return new Evidence(EvidenceType.REPOSITORY, path, start, end, null, null, quote);
    }

    private static Evidence doc(String version, int chunk, String quote) {
        return new Evidence(EvidenceType.DOCUMENT, null, null, null, version, chunk, quote);
    }

    private static VerifiedItem only(Item item) {
        return EvidenceVerifier.verify(new Proposal(null, List.of(item), List.of(), List.of()), Map.of(PATH, CODE), DOCS).get(0);
    }

    @Test
    void validRepositoryCitationIsVerifiedWithAComputedRangeHash() {
        VerifiedItem item = only(fact("Loans are served by the loan controller at /loans.",
                repo(PATH, 3, 6, "@RequestMapping(\"/loans\")")));
        assertThat(item.kind()).isEqualTo(Kind.FACT);
        assertThat(item.supportState()).isEqualTo("VERIFIED");
        assertThat(item.evidence()).singleElement().satisfies(e -> {
            assertThat(e.rangeSha256()).isEqualTo(InputSets.sha256(String.join("\n",
                    CODE.lines().toList().subList(2, 6))));
            assertThat(e.supportPassed()).isTrue();
        });
    }

    @Test
    void forgedPathOutOfRangeLinesAndWrongQuoteAreRemovedAndTheFactBecomesAnAssumption() {
        for (Evidence forged : List.of(repo("src/Missing.java", 1, 2, "class"),
                repo(PATH, 50, 60, "@RequestMapping"), repo(PATH, 0, 2, "package"), repo(PATH, 4, 2, "class"),
                repo(PATH, 1, 2, "this text is not in the file"), repo(PATH, 1, 300, "package com.acme"))) {
            VerifiedItem item = only(fact("Loans are served at /loans.", forged));
            assertThat(item.kind()).as(forged.toString()).isEqualTo(Kind.ASSUMPTION);
            assertThat(item.proposedKind()).isEqualTo(Kind.FACT);
            assertThat(item.section()).isEqualTo("ASSUMPTIONS");
            assertThat(item.supportState()).isEqualTo("UNSUPPORTED");
            assertThat(item.evidence()).isEmpty();
            assertThat(item.rejectedCitations()).isEqualTo(1);
        }
    }

    @Test
    void documentCitationsMustBeInTheInputSetChunkAndQuote() {
        VerifiedItem ok = only(fact("Repayments need a channel code.", doc(DOC.toString(), 1, "carry a channel code")));
        assertThat(ok.supportState()).isEqualTo("VERIFIED");
        assertThat(ok.evidence().get(0).chunkLabel()).isEqualTo("Section 2");
        for (Evidence forged : List.of(doc(UUID.randomUUID().toString(), 1, "carry a channel code"),
                doc(DOC.toString(), 2, "carry a channel code"), doc(DOC.toString(), 1, "carry an invoice number"),
                doc("not-a-uuid", 1, "carry a channel code"))) {
            VerifiedItem item = only(fact("Repayments need a channel code.", forged));
            assertThat(item.kind()).as(forged.toString()).isEqualTo(Kind.ASSUMPTION);
            assertThat(item.supportState()).isEqualTo("UNSUPPORTED");
        }
    }

    @Test
    void aValidCitationThatDoesNotSupportTheStatementIsFlagged() {
        VerifiedItem item = only(fact("The billing module encrypts invoices.", repo(PATH, 4, 4, "class LoanController")));
        assertThat(item.kind()).isEqualTo(Kind.FACT);
        assertThat(item.supportState()).isEqualTo("FLAGGED");
        assertThat(item.evidence().get(0).supportPassed()).isFalse();
    }

    @Test
    void whitespaceDifferencesInQuotesAreTolerated() {
        assertThat(EvidenceVerifier.contains("a   b\n  c", "a b c")).isTrue();
        assertThat(EvidenceVerifier.contains("abc", "  ")).isFalse();
    }

    @Test
    void unsupportedRequirementsAndUncitedRecommendationsAreListedAsUnsupported() {
        List<VerifiedItem> items = EvidenceVerifier.verify(new Proposal(null,
                List.of(new Item(Kind.RECOMMENDATION, Section.RECOMMENDATIONS, "Add an index on repayments.", List.of(),
                        "low", "db")),
                List.of(new Unsupported("Export of audit data as CSV")), List.of()), Map.of(), Map.of());
        assertThat(items).extracting(VerifiedItem::supportState).containsExactly("UNSUPPORTED", "UNSUPPORTED");
        assertThat(items.get(1).section()).isEqualTo("UNSUPPORTED_REQUIREMENTS");
        assertThat(items).extracting(VerifiedItem::itemKey).doesNotHaveDuplicates()
                .allSatisfy(k -> assertThat(k).matches("^[a-z0-9-]{1,80}$"));
    }

    @Test
    void itemKeysAreStableForTheSameStatement() {
        Item item = new Item(Kind.ASSUMPTION, Section.ASSUMPTIONS, "Same text.", List.of(), null, null);
        String a = EvidenceVerifier.verify(new Proposal(null, List.of(item), List.of(), List.of()), Map.of(), Map.of()).get(0).itemKey();
        String b = EvidenceVerifier.verify(new Proposal(null, List.of(item), List.of(), List.of()), Map.of(), Map.of()).get(0).itemKey();
        assertThat(a).isEqualTo(b);
    }

    private static final EvidenceVerifier.RequirementSource REQ = new EvidenceVerifier.RequirementSource(2, Map.of(
            "description", "Generate a portfolio website from an uploaded resume in PDF or DOCX format.",
            "businessObjective", "Remove the manual effort of building a portfolio site."));

    private static VerifiedItem withRequirement(Item item) {
        return EvidenceVerifier.verify(new Proposal(null, List.of(item), List.of(), List.of()), Map.of(), Map.of(), REQ)
                .get(0);
    }

    private static Evidence requirement(String field, String quote) {
        return new Evidence(EvidenceType.REQUIREMENT, field, null, null, null, null, quote);
    }

    @Test
    void aQuoteOfTheClientsRequirementVerifies() {
        VerifiedItem item = withRequirement(fact("Resumes are uploaded as PDF or DOCX files.",
                requirement("description", "uploaded resume in PDF or DOCX format")));
        assertThat(item.kind()).isEqualTo(Kind.FACT);
        assertThat(item.supportState()).isEqualTo("VERIFIED");
        assertThat(item.evidence()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EvidenceType.REQUIREMENT);
            assertThat(e.path()).isEqualTo("description");
            assertThat(e.chunkLabel()).isEqualTo("Description");
            assertThat(e.revisionNo()).isEqualTo(2);
        });
        // The field may be named by its label too.
        assertThat(withRequirement(fact("Manual effort is removed.",
                requirement("Business objective", "Remove the manual effort"))).supportState()).isEqualTo("VERIFIED");
    }

    @Test
    void anInventedOrMisplacedRequirementQuoteIsRejected() {
        // Not in the requirement at all.
        VerifiedItem invented = withRequirement(fact("Users sign in with LinkedIn.",
                requirement("description", "sign in with LinkedIn")));
        assertThat(invented.kind()).isEqualTo(Kind.ASSUMPTION);
        assertThat(invented.rejectedCitations()).isEqualTo(1);
        // Real text, wrong field; unknown field; and no requirement source at all.
        assertThat(withRequirement(fact("Resumes are PDF or DOCX.", requirement("businessObjective", "PDF or DOCX")))
                .evidence()).isEmpty();
        assertThat(withRequirement(fact("Resumes are PDF or DOCX.", requirement("notes", "PDF or DOCX"))).evidence())
                .isEmpty();
        assertThat(only(fact("Resumes are PDF or DOCX.", requirement("description", "PDF or DOCX"))).evidence())
                .isEmpty();
    }
}
