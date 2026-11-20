package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.tony079.sdlc.businessanalysis.internal.DesignContracts.Item;
import io.github.tony079.sdlc.businessanalysis.internal.DesignContracts.Kind;
import io.github.tony079.sdlc.businessanalysis.internal.DesignContracts.Section;

import org.junit.jupiter.api.Test;

/** GD-30: bundled design items (as in REQ-000003's design version 2) become one reviewable item per point. */
class DesignSplitterTest {

    private static Item item(Section section, String title, String detail) {
        return new Item(section, Kind.NEW, title, detail, "Backend REST API service", List.of("brd-1"), List.of());
    }

    @Test
    void anApiItemListingEndpointsBecomesOneItemPerEndpoint() {
        List<Item> out = DesignSplitter.split(List.of(item(Section.API_CHANGES, "REST API endpoints",
                "Key API endpoints include: POST /api/uploadResume - accepts multipart file upload, validates PDF/DOCX "
                        + "and size limits; GET /api/parsedResume/{resumeId} - returns extracted JSON for user review; "
                        + "PUT /api/parsedResume/{resumeId} - overrides parsed fields with user edits; GET /api/auditLogs "
                        + "- for admin users to query AI generated content logs. APIs secure with OAuth tokens, validate "
                        + "inputs, and handle errors with clear codes and messages.")));
        assertThat(out).extracting(Item::title).containsExactly("POST /api/uploadResume",
                "GET /api/parsedResume/{resumeId}", "PUT /api/parsedResume/{resumeId}", "GET /api/auditLogs",
                "REST API endpoints: common rules");
        assertThat(out.get(0).detail()).isEqualTo(
                "POST /api/uploadResume: accepts multipart file upload, validates PDF/DOCX and size limits");
        assertThat(out.get(3).detail()).doesNotContain("OAuth");
        assertThat(out.get(4).detail()).startsWith("APIs secure with OAuth tokens");
        assertThat(out).allSatisfy(i -> assertThat(i.brdRefs()).containsExactly("brd-1"));
    }

    @Test
    void aDataModelItemListingTablesBecomesOneItemPerTable() {
        List<Item> out = DesignSplitter.split(List.of(item(Section.DATA_MODEL, "Database schema",
                "Key tables include: Users (id UUID PK, email varchar unique, created_at TIMESTAMP); Portfolios (id UUID "
                        + "PK, user_id FK, status enum (draft, published), created_at TIMESTAMP); Templates (id UUID PK, "
                        + "name varchar). Constraints enforce referential integrity and user ownership of portfolios.")));
        assertThat(out).extracting(Item::title).containsExactly("Table Users", "Table Portfolios", "Table Templates",
                "Database schema: other points");
        assertThat(out.get(1).detail()).contains("status enum (draft, published)");
        assertThat(out.get(3).detail()).startsWith("Constraints enforce referential integrity");
    }

    @Test
    void aNumberedDetailIsSplitAndSinglePointsAreLeftAlone() {
        List<Item> out = DesignSplitter.split(List.of(item(Section.TEST_PLAN, "Tests",
                "Unit and integration tests for: 1) resume upload with validation; 2) template switching; 3) publishing.")));
        assertThat(out).extracting(Item::title).containsExactly("Tests: resume upload with validation",
                "Tests: template switching", "Tests: publishing");
        assertThat(out).extracting(Item::detail).containsExactly("resume upload with validation.", "template switching.",
                "publishing.");
        Item single = item(Section.API_CHANGES, "Upload", "POST /api/resumes accepts a PDF or DOCX file up to 10 MB.");
        assertThat(DesignSplitter.split(List.of(single))).containsExactly(single);
    }

    @Test
    void endpointsSharingOneDescriptionBothGetItAndAShortLeadInStaysWithThem() {
        List<Item> out = DesignSplitter.split(List.of(item(Section.API_CHANGES, "Serve pages and assets through the same gate",
                "On visitor origin, GET /s/{slug}/ and GET /s/{slug}/assets/{digest}/{name} take no body and return 200 "
                        + "only after checking protection. POST /s/{slug}/unlock {password?:string} returns 204 or 401. "
                        + "Use a fragment for token links and never log it.")));
        assertThat(out).extracting(Item::title).containsExactly("GET /s/{slug}/", "GET /s/{slug}/assets/{digest}/{name}",
                "POST /s/{slug}/unlock", "Serve pages and assets through the same gate: common rules");
        assertThat(out.get(0).detail()).isEqualTo("On visitor origin, GET /s/{slug}/: take no body and return 200 only "
                + "after checking protection.");
        assertThat(out.get(1).detail()).startsWith("On visitor origin, GET /s/{slug}/assets/{digest}/{name}: take no body");
        assertThat(out.get(3).detail()).isEqualTo("Use a fragment for token links and never log it.");
    }

    @Test
    void aSecondPathWithoutItsOwnMethodJoinsTheTitle() {
        List<Item> out = DesignSplitter.split(List.of(item(Section.API_CHANGES, "Visitor gateway",
                "POST /s/{slug}/access: {password?:string} returns 204 or 401. GET /s/{slug}/ and /s/{slug}/assets/{path} "
                        + "returns 200 HTML or a manifest-listed asset; 404 for unknown paths.")));
        assertThat(out).extracting(Item::title).contains("GET /s/{slug}/ and /s/{slug}/assets/{path}");
        assertThat(out).filteredOn(i -> i.title().startsWith("GET")).singleElement().extracting(Item::detail)
                .isEqualTo("GET /s/{slug}/ and /s/{slug}/assets/{path}: returns 200 HTML or a manifest-listed asset; "
                        + "404 for unknown paths.");
    }
}
