package io.github.tony079.sdlc.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small, synthetic sample files (benign and hostile) for attachment tests. No real documents or malware. */
public final class Samples {

    private Samples() {
    }

    public static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdf(String marker) {
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n% " + marker + "\ntrailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] docx(String text) {
        return zip(Map.of(
                "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml", "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>"
                        + text + "</w:t></w:r></w:p></w:body></w:document>"));
    }

    public static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** A DOCX-shaped archive whose one entry expands to {@code megabytes} MB of zeros (decompression bomb). */
    public static byte[] docxBomb(int megabytes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < megabytes; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** A Windows executable header renamed to a document type: contains NUL bytes and no document structure. */
    public static byte[] executable() {
        byte[] exe = new byte[512];
        exe[0] = 'M';
        exe[1] = 'Z';
        exe[60] = (byte) 0x80;
        return exe;
    }
}
