package io.github.tony079.sdlc.document.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Validates a file's actual content against its claimed type (PRD FR-UPL-05/06, FR-STO-10, TDD 15.2): magic bytes
 * and structure, not the extension. Returns a content-free reason code when the file is rejected.
 *
 * <ul>
 *   <li>PDF: {@code %PDF-} header near the start and {@code %%EOF} near the end.</li>
 *   <li>DOCX: a ZIP with {@code [Content_Types].xml} and {@code word/document.xml}; bounded entry count, expanded
 *       size and compression ratio (decompression bombs), no absolute or parent-relative entry names (path
 *       traversal), no macro project.</li>
 *   <li>TXT/Markdown: strictly valid UTF-8, or UTF-16 with a byte-order mark; no NUL bytes (binary content,
 *       renamed executables).</li>
 * </ul>
 *
 * <p>Only structure is inspected here; text extraction happens later in the isolated extraction step (O-15).
 */
final class DocumentContentValidator {

    static final int MAX_ZIP_ENTRIES = 2000;
    static final long MAX_ZIP_EXPANDED_BYTES = 200L * 1024 * 1024;
    static final int MAX_ZIP_RATIO = 100;

    private DocumentContentValidator() {
    }

    static Optional<String> validate(Path file, DocumentType claimed) throws IOException {
        return switch (claimed) {
            case PDF -> validatePdf(file);
            case DOCX -> validateDocx(file);
            case TXT, MARKDOWN -> validateText(file);
        };
    }

    private static Optional<String> validatePdf(Path file) throws IOException {
        long size = Files.size(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[(int) Math.min(1024, size)];
            raf.readFully(head);
            if (!contains(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                return Optional.of("CONTENT_TYPE_MISMATCH");
            }
            int tailLength = (int) Math.min(2048, size);
            byte[] tail = new byte[tailLength];
            raf.seek(size - tailLength);
            raf.readFully(tail);
            if (!contains(tail, "%%EOF".getBytes(StandardCharsets.US_ASCII))) {
                return Optional.of("CORRUPT_PDF");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateDocx(Path file) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(magic, 0, 4) != 4 || magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
                return Optional.of("CONTENT_TYPE_MISMATCH");
            }
        }
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) {
                return Optional.of("ARCHIVE_LIMITS_EXCEEDED");
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("..") || name.contains(":")
                        || name.chars().anyMatch(c -> c < 0x20)) {
                    return Optional.of("UNSAFE_ARCHIVE_ENTRY");
                }
                if (!names.add(name)) {
                    return Optional.of("UNSAFE_ARCHIVE_ENTRY");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // Count the real decompressed bytes: headers can lie about sizes.
                long entryBytes = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        entryBytes += read;
                        expanded += read;
                        if (expanded > MAX_ZIP_EXPANDED_BYTES) {
                            return Optional.of("ARCHIVE_LIMITS_EXCEEDED");
                        }
                    }
                }
                long compressed = Math.max(1, entry.getCompressedSize());
                if (entryBytes > 1024 * 1024 && entryBytes / compressed > MAX_ZIP_RATIO) {
                    return Optional.of("ARCHIVE_LIMITS_EXCEEDED");
                }
            }
        } catch (ZipException e) {
            return Optional.of("CORRUPT_DOCX");
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml")) {
            return Optional.of("CONTENT_TYPE_MISMATCH");
        }
        if (names.stream().anyMatch(n -> n.toLowerCase().endsWith("vbaproject.bin"))) {
            return Optional.of("MACROS_NOT_ALLOWED");
        }
        return Optional.empty();
    }

    private static Optional<String> validateText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        boolean utf16 = bytes.length >= 2
                && ((bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) || (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE));
        if (!utf16) {
            for (byte b : bytes) {
                if (b == 0) {
                    return Optional.of("BINARY_CONTENT");
                }
            }
        }
        CharsetDecoder decoder = (utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            if (utf16 && text.indexOf('\0') >= 0) {
                return Optional.of("BINARY_CONTENT");
            }
        } catch (CharacterCodingException e) {
            return Optional.of("INVALID_TEXT_ENCODING");
        }
        return Optional.empty();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
