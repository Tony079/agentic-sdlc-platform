package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Renders a {@link BrdDocument} as a Word (.docx, Office Open XML) file: real heading styles (so Word builds a
 * navigation pane and table of contents), a metadata table, bulleted statements, a running header and page numbers.
 * Written directly as OOXML parts; no third-party library is needed for this fixed layout.
 */
final class BrdDocxRenderer {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final StringBuilder body = new StringBuilder();

    private BrdDocxRenderer() {
    }

    static byte[] render(BrdDocument d) {
        BrdDocxRenderer r = new BrdDocxRenderer();
        r.write(d);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "docProps/core.xml", core(d));
            put(zip, "word/document.xml", r.document());
            put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            put(zip, "word/styles.xml", STYLES);
            put(zip, "word/numbering.xml", NUMBERING);
            put(zip, "word/header1.xml", header(d));
            put(zip, "word/footer1.xml", FOOTER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private void write(BrdDocument d) {
        para("Kicker", run("BUSINESS REQUIREMENTS DOCUMENT", false));
        para("Title", run(d.requirementTitle(), false));
        para("Subtitle", run(d.requirementKey() + "  ·  Version " + d.versionNo() + "  ·  Approved", false));
        table(List.of(
                new BrdDocument.Field("Organisation", d.organisation()),
                new BrdDocument.Field("Project", d.project()),
                new BrdDocument.Field("Requirement", d.requirementKey() + " — " + d.requirementTitle()),
                new BrdDocument.Field("BRD version", d.versionNo() + ", produced " + d.producedAt()),
                new BrdDocument.Field("Feasibility", d.feasibility() == null ? "Not assessed" : d.feasibility()),
                new BrdDocument.Field("Exported", d.generatedAt() + " by " + d.generatedBy())));

        int n = 1;
        if (!d.requirement().isEmpty()) {
            para("Heading1", run(n++ + ". Requirement", false));
            for (BrdDocument.Field f : d.requirement()) {
                para("Label", run(f.label(), false));
                lines("Normal", f.value());
            }
        }
        if (d.feasibility() != null) {
            para("Heading1", run(n++ + ". Feasibility: " + d.feasibility(), false));
            d.feasibilityReasons().forEach(reason -> bullet(run(reason, false)));
        }
        if (d.summary() != null && !d.summary().isBlank()) {
            para("Heading1", run(n++ + ". Summary", false));
            lines("Normal", d.summary());
        }
        for (BrdDocument.Section s : d.sections()) {
            para("Heading1", run(n++ + ". " + s.title(), false));
            for (BrdDocument.Entry e : s.entries()) {
                bullet(tag(e.kind().toUpperCase()) + run(" " + e.statement(), false));
                para("Detail", run(e.details(), false));
                e.evidence().forEach(ev -> para("Detail", run("Evidence: " + ev, false)));
            }
        }
        if (!d.excludedDocuments().isEmpty()) {
            para("Heading1", run(n++ + ". Documents not analysed", false));
            d.excludedDocuments().forEach(doc -> bullet(run(doc, false)));
        }
        para("Heading1", run(n + ". Approval record", false));
        table(d.approval());
        para("Detail", run("This document was generated from the approved BRD version stored in Garuda. The content "
                + "fingerprint identifies that version exactly; any change to the content produces a different "
                + "fingerprint.", false));
    }

    private String document() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="%s"><w:body>%s<w:sectPr>\
                <w:headerReference w:type="default" r:id="rIdHeader"/><w:footerReference w:type="default" r:id="rIdFooter"/>\
                <w:pgSz w:w="11906" w:h="16838"/>\
                <w:pgMar w:top="1418" w:right="1134" w:bottom="1304" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/>\
                </w:sectPr></w:body></w:document>""".formatted(W, R, body);
    }

    // --- building blocks ---------------------------------------------------------------------------------------

    private void para(String style, String runs) {
        body.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>").append(runs).append("</w:p>");
    }

    /** Line breaks in stored text become separate paragraphs. */
    private void lines(String style, String value) {
        for (String line : value.split("\\R")) {
            para(style, run(line, false));
        }
    }

    private void bullet(String runs) {
        body.append("<w:p><w:pPr><w:pStyle w:val=\"ListBullet\"/><w:numPr><w:ilvl w:val=\"0\"/>")
                .append("<w:numId w:val=\"1\"/></w:numPr></w:pPr>").append(runs).append("</w:p>");
    }

    private void table(List<BrdDocument.Field> rows) {
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"D6DBE3\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"D6DBE3\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"D6DBE3\"/></w:tblBorders>")
                .append("<w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"120\" w:type=\"dxa\"/>")
                .append("<w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"120\" w:type=\"dxa\"/></w:tblCellMar>")
                .append("</w:tblPr><w:tblGrid><w:gridCol w:w=\"2800\"/><w:gridCol w:w=\"6838\"/></w:tblGrid>");
        for (BrdDocument.Field row : rows) {
            body.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2800\" w:type=\"dxa\"/><w:shd w:val=\"clear\" ")
                    .append("w:color=\"auto\" w:fill=\"F3F5F9\"/></w:tcPr><w:p><w:pPr><w:pStyle w:val=\"TableLabel\"/>")
                    .append("</w:pPr>").append(run(row.label(), false)).append("</w:p></w:tc>")
                    .append("<w:tc><w:tcPr><w:tcW w:w=\"6838\" w:type=\"dxa\"/></w:tcPr><w:p><w:pPr>")
                    .append("<w:pStyle w:val=\"TableText\"/></w:pPr>").append(run(row.value(), false))
                    .append("</w:p></w:tc></w:tr>");
        }
        body.append("</w:tbl><w:p/>");
    }

    private static String run(String text, boolean bold) {
        return "<w:r>" + (bold ? "<w:rPr><w:b/></w:rPr>" : "") + "<w:t xml:space=\"preserve\">" + xml(text)
                + "</w:t></w:r>";
    }

    private static String tag(String text) {
        return "<w:r><w:rPr><w:b/><w:color w:val=\"1D4ED8\"/><w:sz w:val=\"15\"/></w:rPr><w:t xml:space=\"preserve\">"
                + xml(text) + "</w:t></w:r>";
    }

    /** XML-escapes and drops characters XML 1.0 cannot carry. */
    static String xml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    boolean allowed = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
                    if (allowed) {
                        b.appendCodePoint(cp);
                    }
                }
            }
        });
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // --- package parts ---------------------------------------------------------------------------------------------

    private static String core(BrdDocument d) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" \
                xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\
                <dc:title>%s</dc:title><dc:subject>%s</dc:subject><dc:creator>%s</dc:creator>\
                <cp:keywords>BRD; %s; v%d</cp:keywords>\
                <dcterms:created xsi:type="dcterms:W3CDTF">%s</dcterms:created></cp:coreProperties>"""
                .formatted(xml(d.requirementKey() + " Business Requirements Document v" + d.versionNo()),
                        xml(d.requirementTitle()), xml(d.organisation()), xml(d.requirementKey()), d.versionNo(),
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    private static String header(BrdDocument d) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:hdr xmlns:w="%s"><w:p><w:pPr><w:pStyle w:val="HeaderText"/>\
                <w:tabs><w:tab w:val="right" w:pos="9638"/></w:tabs></w:pPr>\
                <w:r><w:t xml:space="preserve">%s  ·  Confidential</w:t></w:r><w:r><w:tab/></w:r>\
                <w:r><w:t xml:space="preserve">%s  ·  BRD v%d</w:t></w:r></w:p></w:hdr>"""
                .formatted(W, xml(d.organisation()), xml(d.requirementKey()), d.versionNo());
    }

    private static final String FOOTER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr>\
            <w:pStyle w:val="HeaderText"/><w:tabs><w:tab w:val="right" w:pos="9638"/></w:tabs></w:pPr>\
            <w:r><w:t xml:space="preserve">Generated by Garuda</w:t></w:r><w:r><w:tab/></w:r>\
            <w:r><w:t xml:space="preserve">Page </w:t></w:r>\
            <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>\
            <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>\
            <w:r><w:t xml:space="preserve"> of </w:t></w:r>\
            <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> NUMPAGES </w:instrText></w:r>\
            <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>\
            </w:p></w:ftr>""";

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
            <Default Extension="xml" ContentType="application/xml"/>\
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>\
            <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>\
            <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>\
            <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>\
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>\
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>\
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>\
            </Relationships>""";

    private static final String DOCUMENT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
            <Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>\
            <Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>\
            <Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>\
            </Relationships>""";

    private static final String NUMBERING = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\
            <w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/>\
            <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/>\
            <w:pPr><w:ind w:left="360" w:hanging="360"/></w:pPr><w:rPr><w:color w:val="1D4ED8"/></w:rPr></w:lvl>\
            </w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>""";

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\
            <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/>\
            <w:color w:val="1F2937"/><w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-GB"/></w:rPr></w:rPrDefault>\
            <w:pPrDefault><w:pPr><w:spacing w:after="80" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>\
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>\
            <w:style w:type="paragraph" w:styleId="Kicker"><w:name w:val="Kicker"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="40"/></w:pPr><w:rPr><w:b/><w:color w:val="1D4ED8"/><w:spacing w:val="20"/><w:sz w:val="17"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/>\
            <w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:qFormat/>\
            <w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:color w:val="5B6575"/><w:sz w:val="22"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>\
            <w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/>\
            <w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="D6DBE3"/></w:pBdr><w:outlineLvl w:val="0"/></w:pPr>\
            <w:rPr><w:b/><w:sz w:val="27"/><w:szCs w:val="27"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Label"><w:name w:val="Label"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:keepNext/><w:spacing w:before="80" w:after="20"/></w:pPr><w:rPr><w:b/><w:color w:val="5B6575"/><w:sz w:val="18"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="TableLabel"><w:name w:val="Table Label"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:b/><w:color w:val="5B6575"/><w:sz w:val="17"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:sz w:val="19"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:keepNext/><w:spacing w:before="120" w:after="20"/></w:pPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Detail"><w:name w:val="Detail"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="20"/><w:ind w:left="360"/></w:pPr><w:rPr><w:color w:val="5B6575"/><w:sz w:val="16"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="HeaderText"><w:name w:val="Header Text"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="0"/></w:pPr><w:rPr><w:color w:val="5B6575"/><w:sz w:val="15"/></w:rPr></w:style>\
            </w:styles>""";
}
