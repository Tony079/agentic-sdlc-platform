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
}
