package io.github.tony079.sdlc.businessanalysis.internal;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Renders a {@link BrdDocument} as an A4 PDF with embedded (subset) DejaVu Sans, so any language the model wrote in
 * prints correctly. Layout: title block, metadata table, numbered sections, a running header and "page x of n" footer.
 */
final class BrdPdfRenderer {

    private static final float MARGIN = 56;
    private static final float TOP = 64;
    private static final float BOTTOM = 64;
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final Color INK = new Color(0x1f, 0x29, 0x37);
    private static final Color MUTED = new Color(0x5b, 0x65, 0x75);
    private static final Color ACCENT = new Color(0x1d, 0x4e, 0xd8);
    private static final Color RULE = new Color(0xd6, 0xdb, 0xe3);
    private static final Color BAND = new Color(0xf3, 0xf5, 0xf9);

    private final PDDocument pdf = new PDDocument();
    private final PDFont regular;
    private final PDFont bold;
    private PDPageContentStream out;
    private float y;

    private BrdPdfRenderer() {
        regular = font("fonts/DejaVuSans.ttf");
        bold = font("fonts/DejaVuSans-Bold.ttf");
    }

    static byte[] render(BrdDocument d) {
        BrdPdfRenderer r = new BrdPdfRenderer();
        try (PDDocument pdf = r.pdf) {
            r.write(d);
            r.out.close();
            r.decorate(d);
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(d.requirementKey() + " " + d.kind().title + " v" + d.versionNo());
            info.setSubject(d.requirementTitle());
            info.setAuthor(d.organisation());
            info.setCreator("Garuda");
            info.setCreationDate(Calendar.getInstance());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdf.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(BrdDocument d) throws IOException {
        newPage();
        text(d.kind().title.toUpperCase(java.util.Locale.ROOT), bold, 9, ACCENT, 0, 14);
        text(d.requirementTitle(), bold, 20, INK, 0, 26);
        text(d.requirementKey() + "  ·  Version " + d.versionNo() + "  ·  " + d.status(), regular, 11, MUTED, 0, 22);
        y -= 4;
        table(d.cover());

        int n = 1;
        if (!d.requirement().isEmpty()) {
            heading(n++ + ". Requirement");
            for (BrdDocument.Field f : d.requirement()) {
                text(f.label(), bold, 9.5f, INK, 0, 14);
                paragraph(f.value(), regular, 10, INK, 0);
                y -= 4;
            }
        }
        if (d.feasibility() != null) {
            heading(n++ + ". Feasibility: " + d.feasibility());
            for (String reason : d.feasibilityReasons()) {
                bullet(reason);
            }
        }
        if (d.summary() != null && !d.summary().isBlank()) {
            heading(n++ + ". Summary");
            paragraph(d.summary(), regular, 10, INK, 0);
        }
        for (BrdDocument.Section s : d.sections()) {
            heading(n++ + ". " + s.title());
            for (BrdDocument.Entry e : s.entries()) {
                entry(e);
            }
        }
        if (!d.excludedDocuments().isEmpty()) {
            heading(n++ + ". Documents not analysed");
            for (String doc : d.excludedDocuments()) {
                bullet(doc);
            }
        }
        heading(n + ". Approval record");
        table(d.approval());
        y -= 6;
        paragraph("This document was generated from the approved " + d.kind().shortName + " version stored in Garuda. The content fingerprint "
                + "identifies that version exactly; any change to the content produces a different fingerprint.",
                regular, 8.5f, MUTED, 0);
    }

    // --- building blocks ---------------------------------------------------------------------------------------

    private void heading(String title) throws IOException {
        ensure(48);
        y -= 12;
        text(title, bold, 13, INK, 0, 18);
        line(MARGIN, y - 1, MARGIN + WIDTH, y - 1, RULE, 0.6f);
        y -= 10;
    }

    private void entry(BrdDocument.Entry e) throws IOException {
        ensure(36);
        String tag = e.kind().toUpperCase() + "  ";
        float tagWidth = width(tag, bold, 7.5f);
        // The kind tag sits on the first line; the statement wraps after it.
        List<String> lines = wrap(e.statement(), regular, 10, WIDTH - 12 - tagWidth, WIDTH - 12);
        float lineHeight = 14;
        ensure(lineHeight * Math.min(lines.size(), 3));
        draw(tag, bold, 7.5f, ACCENT, MARGIN + 12, y - 10);
        for (int i = 0; i < lines.size(); i++) {
            ensure(lineHeight);
            draw(lines.get(i), regular, 10, INK, MARGIN + 12 + (i == 0 ? tagWidth : 0), y - 10);
            y -= lineHeight;
        }
        paragraph(e.details(), regular, 8, MUTED, 12);
        for (String ev : e.evidence()) {
            paragraph("Evidence: " + ev, regular, 8, MUTED, 12);
        }
        y -= 6;
    }

    private void bullet(String value) throws IOException {
        List<String> lines = wrap(value, regular, 10, WIDTH - 14, WIDTH - 14);
        for (int i = 0; i < lines.size(); i++) {
            ensure(14);
            if (i == 0) {
                draw("•", bold, 10, ACCENT, MARGIN + 2, y - 10);
            }
            draw(lines.get(i), regular, 10, INK, MARGIN + 14, y - 10);
            y -= 14;
        }
        y -= 2;
    }

    private void table(List<BrdDocument.Field> rows) throws IOException {
        float labelWidth = 150;
        for (BrdDocument.Field row : rows) {
            List<String> lines = wrap(row.value() == null ? "" : row.value(), regular, 9.5f,
                    WIDTH - labelWidth - 16, WIDTH - labelWidth - 16);
            List<String> labels = wrap(row.label(), bold, 8.5f, labelWidth - 16, labelWidth - 16);
            float h = Math.max(1, Math.max(lines.size(), labels.size())) * 13 + 8;
            ensure(h);
            fill(MARGIN, y - h, labelWidth, h, BAND);
            line(MARGIN, y - h, MARGIN + WIDTH, y - h, RULE, 0.5f);
            float ly = y - 13;
            for (String l : labels) {
                draw(l, bold, 8.5f, MUTED, MARGIN + 8, ly);
                ly -= 13;
            }
            float ty = y - 13;
            for (String l : lines) {
                draw(l, regular, 9.5f, INK, MARGIN + labelWidth + 8, ty);
                ty -= 13;
            }
            y -= h;
        }
        y -= 8;
    }

    private void paragraph(String value, PDFont font, float size, Color color, float indent) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        float lineHeight = size * 1.4f;
        for (String para : value.split("\\R")) {
            for (String l : wrap(para, font, size, WIDTH - indent, WIDTH - indent)) {
                ensure(lineHeight);
                draw(l, font, size, color, MARGIN + indent, y - size);
                y -= lineHeight;
            }
        }
    }

    private void text(String value, PDFont font, float size, Color color, float indent, float advance)
            throws IOException {
        for (String l : wrap(value, font, size, WIDTH - indent, WIDTH - indent)) {
            ensure(advance);
            draw(l, font, size, color, MARGIN + indent, y - size);
            y -= advance;
        }
    }

    // --- pages ---------------------------------------------------------------------------------------------------

    private void ensure(float height) throws IOException {
        if (y - height < BOTTOM) {
            newPage();
        }
    }

    private void newPage() throws IOException {
        if (out != null) {
            out.close();
        }
        PDPage page = new PDPage(PAGE);
        pdf.addPage(page);
        out = new PDPageContentStream(pdf, page);
        y = PAGE.getHeight() - TOP;
    }

    /** Running header and "page x of n" footer, added once the page count is known. */
    private void decorate(BrdDocument d) throws IOException {
        int total = pdf.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = pdf.getPage(i);
            try (PDPageContentStream s = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND,
                    true, true)) {
                out = s;
                float top = PAGE.getHeight() - 34;
                draw(clip(d.organisation() + "  ·  Confidential", 70), regular, 7.5f, MUTED, MARGIN, top);
                String right = d.requirementKey() + "  ·  " + d.kind().shortName + " v" + d.versionNo();
                draw(right, regular, 7.5f, MUTED, MARGIN + WIDTH - width(right, regular, 7.5f), top);
                line(MARGIN, top - 6, MARGIN + WIDTH, top - 6, RULE, 0.5f);
                line(MARGIN, 44, MARGIN + WIDTH, 44, RULE, 0.5f);
                draw("Generated by Garuda", regular, 7.5f, MUTED, MARGIN, 32);
                String pageNo = "Page " + (i + 1) + " of " + total;
                draw(pageNo, regular, 7.5f, MUTED, MARGIN + WIDTH - width(pageNo, regular, 7.5f), 32);
            }
        }
    }

    // --- primitives ----------------------------------------------------------------------------------------------

    private void draw(String value, PDFont font, float size, Color color, float x, float baseline) throws IOException {
        out.beginText();
        out.setFont(font, size);
        out.setNonStrokingColor(color);
        out.newLineAtOffset(x, baseline);
        out.showText(safe(value, font));
        out.endText();
    }

    private void line(float x1, float y1, float x2, float y2, Color color, float w) throws IOException {
        out.setStrokingColor(color);
        out.setLineWidth(w);
        out.moveTo(x1, y1);
        out.lineTo(x2, y2);
        out.stroke();
    }

    private void fill(float x, float y0, float w, float h, Color color) throws IOException {
        out.setNonStrokingColor(color);
        out.addRect(x, y0, w, h);
        out.fill();
    }

    private float width(String value, PDFont font, float size) throws IOException {
        return font.getStringWidth(safe(value, font)) / 1000 * size;
    }

    /** Word wrap; a word wider than the line (a path, a hash) is broken by characters. */
    private List<String> wrap(String value, PDFont font, float size, float first, float rest) throws IOException {
        List<String> lines = new ArrayList<>();
        String text = value == null ? "" : value.replace('\t', ' ').replaceAll("\\s+", " ").strip();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            float limit = lines.isEmpty() ? first : rest;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(candidate, font, size) <= limit) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
                limit = rest;
            }
            String w = word;
            while (width(w, font, size) > limit) {
                int cut = w.length() - 1;
                while (cut > 1 && width(w.substring(0, cut), font, size) > limit) {
                    cut--;
                }
                lines.add(w.substring(0, cut));
                w = w.substring(cut);
                limit = rest;
            }
            current.append(w);
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** Characters the font cannot draw (and control characters) become a replacement mark instead of failing. */
    private static String safe(String value, PDFont font) {
        if (value == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                b.append(' ');
                return;
            }
            String s = new String(Character.toChars(cp));
            try {
                font.encode(s);
                b.append(s);
            } catch (IOException | IllegalArgumentException e) {
                b.append('?');
            }
        });
        return b.toString();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private PDFont font(String resource) {
        try (InputStream in = BrdPdfRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing font " + resource);
            }
            return PDType0Font.load(pdf, in, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
