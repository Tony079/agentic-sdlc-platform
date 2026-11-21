package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** GD-34 phase 2: plan text is inert on GitHub, dependencies link pushed issues, ticks survive a re-push. */
class PlanIssuesTest {

    private static final PlanIssues.Payload PAYLOAD = new PlanIssues.Payload("REQ-000003", "Portfolio", 2, List.of(),
            List.of(), List.of());

    private static PlanIssues.Issue issue(List<String> criteria, List<PlanIssues.Dependency> deps) {
        return new PlanIssues.Issue("story-b", "As a user, I want pages", "Portfolio", "Pages for every section.", 2,
                "14 Dec 2026 to 20 Dec 2026", "Backend", "MUST", 3.0, criteria, deps, List.of("garuda"), "sprint-2");
    }

    @Test
    void planTextCannotMentionPeopleLinkIssuesOrCarryHtml() {
        String text = PlanIssues.text("Ask @alice about #12 <script>x</script>\n- [x] done", 200);
        assertThat(text).doesNotContain("@alice").doesNotContain("#12").doesNotContain("<script>").doesNotContain("\n");
        assertThat(text).contains("@​alice").contains("#​12").contains("&lt;script&gt;");
        assertThat(PlanIssues.text("x".repeat(300), 256)).hasSize(256).endsWith("…");
    }

    @Test
    void dependenciesUseIssueNumbersAndTickedCriteriaStayTicked() {
        PlanIssues.Issue i = issue(List.of("Every section is shown", "Pages load fast"),
                List.of(new PlanIssues.Dependency("story-a", "As a developer, I want the API"),
                        new PlanIssues.Dependency(null, "Something not planned")));
        String fresh = PlanIssues.body(PAYLOAD, i, Map.of("story-a", 7), null);
        assertThat(fresh).contains("- [ ] Every section is shown").contains("- [ ] Pages load fast")
                .contains("- #7 As a developer, I want the API").contains("- Something not planned")
                .contains("**Sprint:** 2 (14 Dec 2026 to 20 Dec 2026)").contains("delivery plan version 2 of REQ-000003");
        String ticked = fresh.replace("- [ ] Pages load fast", "- [x] Pages load fast");
        assertThat(PlanIssues.body(PAYLOAD, i, Map.of("story-a", 7), ticked))
                .contains("- [x] Pages load fast").contains("- [ ] Every section is shown");
    }
}
