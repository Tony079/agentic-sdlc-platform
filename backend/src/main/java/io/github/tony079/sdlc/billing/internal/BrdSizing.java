package io.github.tony079.sdlc.billing.internal;

import io.github.tony079.sdlc.billing.api.BrdQuotes.Basis;

/**
 * RULES_V1: a transparent, rule-based size until the BRD cost analysis agent is approved (O-01). Every quote made with
 * it is shown as estimated. Points: 2 per document, 3 per repository analysed, 1 per 2,000 characters of
 * requirement text. Small up to 4 points, Medium up to 10, Large above.
 */
final class BrdSizing {

    static final String METHOD = "RULES_V1";

    private BrdSizing() {
    }

    static int points(Basis basis) {
        return basis.documents() * 2 + basis.repositories() * 3 + basis.textChars() / 2000;
    }

    static String size(Basis basis) {
        int points = points(basis);
        return points <= 4 ? "SMALL" : points <= 10 ? "MEDIUM" : "LARGE";
    }
}
