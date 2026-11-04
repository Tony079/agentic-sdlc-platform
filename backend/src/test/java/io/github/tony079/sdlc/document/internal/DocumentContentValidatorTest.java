package io.github.tony079.sdlc.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import io.github.tony079.sdlc.support.Samples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Content validation by structure, not extension (PRD FR-UPL-05/06, FR-STO-10; AC-DOC-02, AC-DOC-03). */
class DocumentContentValidatorTest {

    @TempDir
    Path dir;

    private Optional<String> check(byte[] content, DocumentType type) throws IOException {
        Path file = Files.write(dir.resolve("f-" + System.nanoTime()), content);
        return DocumentContentValidator.validate(file, type);
    }

    @Test
    void acceptsValidFilesOfEachType() throws IOException {
        assertThat(check(Samples.pdf("ok"), DocumentType.PDF)).isEmpty();
        assertThat(check(Samples.docx("hello"), DocumentType.DOCX)).isEmpty();
        assertThat(check(Samples.text("Plain text with ümlauts"), DocumentType.TXT)).isEmpty();
        assertThat(check(Samples.text("# Heading\n\n- item"), DocumentType.MARKDOWN)).isEmpty();
        byte[] utf16 = "﻿Hello".getBytes(StandardCharsets.UTF_16BE);
        assertThat(check(utf16, DocumentType.TXT)).isEmpty();
    }

    @Test
    void rejectsRenamedExecutablesAndArchives() throws IOException {
        assertThat(check(Samples.executable(), DocumentType.TXT)).contains("BINARY_CONTENT");
        assertThat(check(Samples.executable(), DocumentType.PDF)).contains("CONTENT_TYPE_MISMATCH");
        assertThat(check(Samples.executable(), DocumentType.DOCX)).contains("CONTENT_TYPE_MISMATCH");
        assertThat(check(Samples.zip(Map.of("payload.exe", "x")), DocumentType.DOCX)).contains("CONTENT_TYPE_MISMATCH");
        assertThat(check(Samples.zip(Map.of("a.txt", "x")), DocumentType.PDF)).contains("CONTENT_TYPE_MISMATCH");
    }

    @Test
    void rejectsHostileArchives() throws IOException {
        assertThat(check(Samples.docxBomb(250), DocumentType.DOCX)).contains("ARCHIVE_LIMITS_EXCEEDED");
        assertThat(check(Samples.docxBomb(20), DocumentType.DOCX)).contains("ARCHIVE_LIMITS_EXCEEDED");
        assertThat(check(Samples.zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", "<d/>",
                "../../etc/cron.d/x", "boom")), DocumentType.DOCX)).contains("UNSAFE_ARCHIVE_ENTRY");
        assertThat(check(Samples.zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", "<d/>",
                "word/vbaProject.bin", "macro")), DocumentType.DOCX)).contains("MACROS_NOT_ALLOWED");
        assertThat(check("PK\u0003\u0004garbage".getBytes(StandardCharsets.ISO_8859_1), DocumentType.DOCX))
                .contains("CORRUPT_DOCX");
    }

    @Test
    void rejectsCorruptOrMisencodedFiles() throws IOException {
        assertThat(check("%PDF-1.7 truncated".getBytes(StandardCharsets.US_ASCII), DocumentType.PDF)).contains("CORRUPT_PDF");
        assertThat(check(new byte[] {(byte) 0xC3, (byte) 0x28, 'a'}, DocumentType.TXT)).contains("INVALID_TEXT_ENCODING");
    }
}
