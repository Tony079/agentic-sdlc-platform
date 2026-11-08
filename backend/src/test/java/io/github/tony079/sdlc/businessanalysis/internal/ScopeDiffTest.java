package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tony079.sdlc.requirement.api.RequirementView.Content;

import org.junit.jupiter.api.Test;

/** C-16 scope diff: additions, modifications and removals with deterministic priorities. */
class ScopeDiffTest {

    private static Content content(String objective, String criteria, String integrations, String constraints,
                                   String title) {
        return new Content("EXISTING_REPOSITORY", title, "Store repayment metadata.", objective, "Outcomes",
                criteria, "https://github.com/a/b", constraints, integrations);
    }

    @Test
    void classifiesChangesAndPriorities() {
        Content before = content("Improve reconciliation.", "Visible on the API.", null, "None", "Repayments");
        Content after = content("Improve reconciliation and refunds.", null, "Ledger system",
                "Data must be encrypted and access controlled.", "Repayments and refunds");
        var changes = ScopeDiff.compare(before, after);
        assertThat(changes).extracting(c -> c.field() + "=" + c.area() + "/" + c.changeType() + "/" + c.priority())
                .containsExactlyInAnyOrder(
                        "title=GENERAL/MODIFICATION/LOW",
                        "businessObjective=BUSINESS_OBJECTIVE/MODIFICATION/HIGH",
                        "acceptanceCriteria=ACCEPTANCE_CRITERIA/REMOVAL/CRITICAL",
                        "integrations=INTEGRATIONS/ADDITION/HIGH",
                        "constraints=SECURITY/MODIFICATION/CRITICAL");
        assertThat(ScopeDiff.compare(before, before)).isEmpty();
        assertThat(ScopeDiff.validPriority("critical")).isTrue();
        assertThat(ScopeDiff.validPriority("URGENT")).isFalse();
    }
}
