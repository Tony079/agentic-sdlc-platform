package io.github.tony079.sdlc.requirement.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;

/**
 * The eight PRD FR-REQ-01 fields and their rules (approved Milestone 2 decisions Q1 and Q2).
 *
 * <ul>
 *   <li>Always (every save): known kind, title present, length limits, repository URL syntax, and no repository
 *       URL on a NEW_PRODUCT requirement.</li>
 *   <li>At submission additionally: every field required except acceptanceCriteria; repositoryUrl required for
 *       EXISTING_REPOSITORY. "None" / "Not applicable" are valid values for constraints and integrations.</li>
 * </ul>
 *
 * <p>The repository URL check is syntactic only (HTTPS, github.com owner/repository pattern, 300 characters). It
 * does not check that the repository exists or is accessible; that belongs to the repository milestone.
 */
record RequirementFields(String kind, String title, String description, String businessObjective,
                         String expectedOutcomes, String acceptanceCriteria, String repositoryUrl,
                         String constraints, String integrations) {

    static final String EXISTING_REPOSITORY = "EXISTING_REPOSITORY";
    static final String NEW_PRODUCT = "NEW_PRODUCT";

    static final int TITLE_MAX = 200;
    static final int DESCRIPTION_MAX = 20_000;
    static final int BUSINESS_OBJECTIVE_MAX = 5_000;
    static final int EXPECTED_OUTCOMES_MAX = 10_000;
    static final int ACCEPTANCE_CRITERIA_MAX = 10_000;
    static final int REPOSITORY_URL_MAX = 300;
    static final int CONSTRAINTS_MAX = 10_000;
    static final int INTEGRATIONS_MAX = 10_000;

    /**
     * https://github.com/OWNER/REPOSITORY. Owner: 1 to 39 letters, digits or single hyphens, not starting or ending
     * with a hyphen. Repository: 1 to 100 letters, digits, '.', '_' or '-', and not "." or "..".
     */
    private static final Pattern GITHUB_REPOSITORY_URL = Pattern.compile(
            "^https://github\\.com/[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}/(?!\\.{1,2}$)[A-Za-z0-9._-]{1,100}$");

    /** Trims every value and turns blank values into null (a blank field counts as not provided). */
    static RequirementFields normalized(String kind, String title, String description, String businessObjective,
                                        String expectedOutcomes, String acceptanceCriteria, String repositoryUrl,
                                        String constraints, String integrations) {
        return new RequirementFields(clean(kind), clean(title), clean(description), clean(businessObjective),
                clean(expectedOutcomes), clean(acceptanceCriteria), clean(repositoryUrl), clean(constraints),
                clean(integrations));
    }

    static RequirementFields of(Content content) {
        return normalized(content.kind(), content.title(), content.description(), content.businessObjective(),
                content.expectedOutcomes(), content.acceptanceCriteria(), content.repositoryUrl(),
                content.constraints(), content.integrations());
    }

    Content toContent() {
        return new Content(kind, title, description, businessObjective, expectedOutcomes, acceptanceCriteria,
                repositoryUrl, constraints, integrations);
    }

    /** Rules that apply to every save, including partial drafts. */
    List<FieldError> draftErrors() {
        List<FieldError> errors = new ArrayList<>();
        if (kind == null) {
            errors.add(new FieldError("kind", "REQUIRED"));
        } else if (!kind.equals(EXISTING_REPOSITORY) && !kind.equals(NEW_PRODUCT)) {
            errors.add(new FieldError("kind", "INVALID_VALUE"));
        }
        if (title == null) {
            errors.add(new FieldError("title", "REQUIRED"));
        }
        maxLength(errors, "title", title, TITLE_MAX);
        maxLength(errors, "description", description, DESCRIPTION_MAX);
        maxLength(errors, "businessObjective", businessObjective, BUSINESS_OBJECTIVE_MAX);
        maxLength(errors, "expectedOutcomes", expectedOutcomes, EXPECTED_OUTCOMES_MAX);
        maxLength(errors, "acceptanceCriteria", acceptanceCriteria, ACCEPTANCE_CRITERIA_MAX);
        maxLength(errors, "constraints", constraints, CONSTRAINTS_MAX);
        maxLength(errors, "integrations", integrations, INTEGRATIONS_MAX);
        if (repositoryUrl != null) {
            if (NEW_PRODUCT.equals(kind)) {
                errors.add(new FieldError("repositoryUrl", "MUST_BE_ABSENT"));
            } else if (repositoryUrl.length() > REPOSITORY_URL_MAX) {
                errors.add(new FieldError("repositoryUrl", "TOO_LONG"));
            } else if (!GITHUB_REPOSITORY_URL.matcher(repositoryUrl).matches()) {
                errors.add(new FieldError("repositoryUrl", "INVALID_FORMAT"));
            }
        }
        return errors;
    }

    /** Draft rules plus completeness, checked when the requirement is submitted. */
    List<FieldError> submitErrors() {
        List<FieldError> errors = new ArrayList<>(draftErrors());
        required(errors, "description", description);
        required(errors, "businessObjective", businessObjective);
        required(errors, "expectedOutcomes", expectedOutcomes);
        required(errors, "constraints", constraints);
        required(errors, "integrations", integrations);
        if (EXISTING_REPOSITORY.equals(kind)) {
            required(errors, "repositoryUrl", repositoryUrl);
        }
        return errors;
    }

    /** SHA-256 over a canonical, unambiguous encoding of every field (length-prefixed, null distinct from empty). */
    String contentHash() {
        return sha256(canonical(kind, title, description, businessObjective, expectedOutcomes, acceptanceCriteria,
                repositoryUrl, constraints, integrations));
    }

    static String canonical(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                out.append("-1:");
            } else {
                out.append(value.length()).append(':').append(value);
            }
            out.append('|');
        }
        return out.toString();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void maxLength(List<FieldError> errors, String field, String value, int max) {
        if (value != null && value.length() > max) {
            errors.add(new FieldError(field, "TOO_LONG"));
        }
    }

    private static void required(List<FieldError> errors, String field, String value) {
        if (value == null && errors.stream().noneMatch(e -> e.field().equals(field))) {
            errors.add(new FieldError(field, "REQUIRED"));
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
