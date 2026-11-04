package io.github.tony079.sdlc.docprocessing.internal.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.github.tony079.sdlc.docprocessing.internal.extractor.ExtractorMain.Result;
import io.github.tony079.sdlc.support.Pdfs;
import io.github.tony079.sdlc.support.Samples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Text extraction rules (PRD FR-DPR-03..06, FR-UPL-12, AC-DOC-10): headings, pages, limits, no OCR, no XXE. */
class ExtractorMainTest {

    @TempDir
    Path dir;

    private Result extract(byte[] content, String type) throws Exception {
        Path file = Files.write(dir.resolve("in-" + System.nanoTime()), content);
        try {
            return ExtractorMain.extract(file, type);
        } catch (Exception e) {
            return Result.failed("EXTRACTION_FAILED_FINAL", "CORRUPT_FILE");
        }
    }

    @Test
    void markdownKeepsHeadingsAsSectionLabels() throws Exception {
        Result result = extract(Samples.text("Intro line\n\n# Repayments\nChannel code is required.\n\n## Limits\nMax 10."),
                "MARKDOWN");
        assertThat(result.outcome()).isEqualTo("PROCESSED");
        assertThat(result.chunks()).extracting(ExtractorMain.Chunk::label).containsExactly(null, "Repayments", "Limits");
        assertThat(result.chunks().get(1).text()).contains("Channel code is required.");
    }

    @Test
    void longTextIsSplitIntoBoundedChunks() throws Exception {
        String word = "repayment ";
        Result result = extract(Samples.text(word.repeat(1000)), "TXT");
        assertThat(result.outcome()).isEqualTo("PROCESSED");
        assertThat(result.chunks()).hasSizeGreaterThan(4).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2000));
    }

    @Test
    void docxParagraphsAndHeadings() throws Exception {
        String xml = "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Scope</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Loans must store </w:t></w:r><w:r><w:t>the channel.</w:t></w:r></w:p></w:body></w:document>";
        Result result = extract(Samples.zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", xml)), "DOCX");
        assertThat(result.outcome()).isEqualTo("PROCESSED");
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).label()).isEqualTo("Scope");
        assertThat(result.chunks().get(0).text()).contains("Loans must store the channel.");
    }

    /** XXE: external entities and DTDs are never resolved (hostile documents are untrusted data). */
    @Test
    void docxExternalEntitiesAreNotResolved() throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-HOST-FILE");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file://" + secret + "\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r>"
                + "<w:t>&x;</w:t></w:r></w:p></w:body></w:document>";
        Result result = extract(Samples.zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", xml)), "DOCX");
        assertThat(result.chunks()).noneMatch(c -> c.text().contains("TOP-SECRET"));
    }

    @Test
    void pdfTextIsExtractedPerPage() throws Exception {
        Result result = extract(Pdfs.withText("First page about loans", "Second page about channels"), "PDF");
        assertThat(result.outcome()).isEqualTo("PROCESSED");
        assertThat(result.chunks()).extracting(ExtractorMain.Chunk::page).containsExactly(1, 2);
        assertThat(result.chunks().get(1).text()).contains("Second page about channels");
        assertThat(result.chunks().get(0).label()).isEqualTo("Page 1");
    }

    /** No OCR in Release 1: image-only and encrypted PDFs are never claimed as analysed (FR-UPL-12, AC-DOC-10). */
    @Test
    void imageOnlyAndEncryptedPdfsAreUnsupportedContent() throws Exception {
        assertThat(extract(Pdfs.imageOnly(), "PDF")).extracting(Result::outcome, Result::reason)
                .containsExactly("UNSUPPORTED_CONTENT", "NO_TEXT_LAYER");
        assertThat(extract(Pdfs.passwordProtected("hidden"), "PDF")).extracting(Result::outcome, Result::reason)
                .containsExactly("UNSUPPORTED_CONTENT", "ENCRYPTED");
    }

    @Test
    void corruptAndOversizedInputsFailFinally() throws Exception {
        assertThat(extract("%PDF-1.7 garbage %%EOF".getBytes(StandardCharsets.US_ASCII), "PDF").outcome())
                .isEqualTo("EXTRACTION_FAILED_FINAL");
        assertThat(extract(Samples.text("x".repeat(2_000_001)), "TXT")).extracting(Result::outcome, Result::reason)
                .containsExactly("EXTRACTION_FAILED_FINAL", "TEXT_LIMIT_EXCEEDED");
        assertThat(extract(Samples.text("   \n\n  "), "TXT")).extracting(Result::outcome, Result::reason)
                .containsExactly("UNSUPPORTED_CONTENT", "NO_TEXT");
    }

    @Test
    void jsonEscapingIsSafe() {
        assertThat(ExtractorMain.json("a\"b\\c\n\u0001")).isEqualTo("\"a\\\"b\\\\c\\n\\u0001\"");
        assertThat(ExtractorMain.json(null)).isEqualTo("null");
    }
}
