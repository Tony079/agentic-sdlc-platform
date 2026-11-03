package io.github.tony079.sdlc.requirement.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.tony079.sdlc.common.api.ApiError.FieldError;

import org.junit.jupiter.api.Test;

/** Field rules from approved decisions Q1 and Q2. */
class RequirementFieldsTest {

    private static RequirementFields complete(String kind, String url) {
        return RequirementFields.normalized(kind, "Title", "Description", "Objective", "Outcomes", null, url,
                "None", "Not applicable");
    }

    private static List<String> codes(List<FieldError> errors) {
        return errors.stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    void completeExistingRepositoryRequirementIsSubmittableWithoutAcceptanceCriteria() {
        assertThat(complete("EXISTING_REPOSITORY", "https://github.com/acme-bank/loan-service").submitErrors()).isEmpty();
    }

    @Test
    void noneAndNotApplicableAreValidForConstraintsAndIntegrations() {
        assertThat(complete("NEW_PRODUCT", null).submitErrors()).isEmpty();
    }

    @Test
    void partialDraftNeedsOnlyKindAndTitle() {
        RequirementFields draft = RequirementFields.normalized("NEW_PRODUCT", "Only a title", null, null, null, null,
                null, null, null);
        assertThat(draft.draftErrors()).isEmpty();
        assertThat(codes(draft.submitErrors())).containsExactlyInAnyOrder("description:REQUIRED",
                "businessObjective:REQUIRED", "expectedOutcomes:REQUIRED", "constraints:REQUIRED",
                "integrations:REQUIRED");
    }

    @Test
    void repositoryUrlIsRequiredAtSubmissionOnlyForExistingRepository() {
        RequirementFields draft = complete("EXISTING_REPOSITORY", null);
        assertThat(draft.draftErrors()).isEmpty();
        assertThat(codes(draft.submitErrors())).containsExactly("repositoryUrl:REQUIRED");
    }

    @Test
    void repositoryUrlMustBeAbsentForNewProduct() {
        assertThat(codes(complete("NEW_PRODUCT", "https://github.com/acme/app").draftErrors()))
                .containsExactly("repositoryUrl:MUST_BE_ABSENT");
    }

    @Test
    void repositoryUrlSyntaxIsHttpsGithubOwnerRepository() {
        for (String valid : List.of("https://github.com/acme/app", "https://github.com/a-b/c.d_e-f",
                "https://github.com/A1/repo.git")) {
            assertThat(complete("EXISTING_REPOSITORY", valid).draftErrors()).as(valid).isEmpty();
        }
        for (String invalid : List.of("http://github.com/acme/app", "https://gitlab.com/acme/app",
                "https://github.com/acme", "https://github.com/acme/app/tree/main", "https://github.com/-acme/app",
                "https://github.com/acme-/app", "https://github.com/ac--me/app", "https://github.com/acme/..",
                "https://github.com/acme/app?x=1", "https://user@github.com/acme/app", "github.com/acme/app",
                "https://github.com/acme/a b")) {
            assertThat(codes(complete("EXISTING_REPOSITORY", invalid).draftErrors())).as(invalid)
                    .containsExactly("repositoryUrl:INVALID_FORMAT");
        }
        String tooLong = "https://github.com/acme/" + "r".repeat(300);
        assertThat(codes(complete("EXISTING_REPOSITORY", tooLong).draftErrors()))
                .containsExactly("repositoryUrl:TOO_LONG");
    }

    @Test
    void lengthLimitsMatchDecisionQ1() {
        RequirementFields atLimits = RequirementFields.normalized("NEW_PRODUCT", "t".repeat(200), "d".repeat(20_000),
                "b".repeat(5_000), "e".repeat(10_000), "a".repeat(10_000), null, "c".repeat(10_000), "i".repeat(10_000));
        assertThat(atLimits.submitErrors()).isEmpty();
        RequirementFields overLimits = RequirementFields.normalized("NEW_PRODUCT", "t".repeat(201), "d".repeat(20_001),
                "b".repeat(5_001), "e".repeat(10_001), "a".repeat(10_001), null, "c".repeat(10_001), "i".repeat(10_001));
        assertThat(codes(overLimits.draftErrors())).containsExactlyInAnyOrder("title:TOO_LONG", "description:TOO_LONG",
                "businessObjective:TOO_LONG", "expectedOutcomes:TOO_LONG", "acceptanceCriteria:TOO_LONG",
                "constraints:TOO_LONG", "integrations:TOO_LONG");
    }

    @Test
    void blankValuesCountAsMissingAndUnknownKindIsRejected() {
        RequirementFields blank = RequirementFields.normalized("  ", "   ", null, null, null, null, null, null, null);
        assertThat(codes(blank.draftErrors())).containsExactlyInAnyOrder("kind:REQUIRED", "title:REQUIRED");
        RequirementFields unknown = RequirementFields.normalized("GREENFIELD", "T", null, null, null, null, null, null, null);
        assertThat(codes(unknown.draftErrors())).containsExactly("kind:INVALID_VALUE");
    }

    @Test
    void contentHashIsStableAndUnambiguous() {
        RequirementFields a = complete("NEW_PRODUCT", null);
        assertThat(a.contentHash()).isEqualTo(complete("NEW_PRODUCT", null).contentHash()).hasSize(64);
        assertThat(RequirementFields.canonical("ab", "c")).isNotEqualTo(RequirementFields.canonical("a", "bc"));
        assertThat(RequirementFields.canonical((String) null)).isNotEqualTo(RequirementFields.canonical(""));
    }

    @Test
    void keysAreZeroPaddedToSixDigitsAndWiden() {
        assertThat(RequirementKeyGenerator.format(123)).isEqualTo("REQ-000123");
        assertThat(RequirementKeyGenerator.format(1_000_000)).isEqualTo("REQ-1000000");
    }
}
