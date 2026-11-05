package io.github.tony079.sdlc.docprocessing.internal.extractor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * The isolated text extractor (TDD 15.5-15.7). Runs as a separate OS process with its own small heap, no inherited
 * environment, no database connection, no storage credentials and no network use: it reads one already-scanned
 * input file from its private workspace and writes {@code chunks.jsonl} and {@code manifest.json} into an output
 * directory. The trusted coordinator in the worker validates everything it writes before anything reaches the
 * database. Parser failures stay inside this process.
 *
 * <p>Outcomes: PROCESSED; UNSUPPORTED_CONTENT (encrypted, or no text layer: image-only PDFs, no OCR in Release 1);
 * EXTRACTION_FAILED_FINAL (corrupt or over the text limits). Usage: {@code ExtractorMain <input> <type> <outDir>}.
 */
public final class ExtractorMain {

    public static final int MAX_CHUNK_CHARS = 2000;
    public static final int MAX_TOTAL_CHARS = 2_000_000;
    public static final int MAX_CHUNKS = 5000;

    record Chunk(String label, Integer page, String text) {
    }

    record Result(String outcome, String reason, List<Chunk> chunks) {
        static Result failed(String outcome, String reason) {
            return new Result(outcome, reason, List.of());
        }
    }

    private ExtractorMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: ExtractorMain <input> <type> <outDir>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        Result result;
        try {
            result = extract(input, args[1]);
        } catch (IOException | RuntimeException | XMLStreamException e) {
            result = Result.failed("EXTRACTION_FAILED_FINAL", "CORRUPT_FILE");
        }
        write(result, out);
    }

    /** Text over the limits: a deterministic failure, never retried. */
    static final class TextLimitExceeded extends RuntimeException {
    }

    static Result extract(Path input, String type) throws IOException, XMLStreamException {
        try {
            return switch (type) {
                case "PDF" -> pdf(input);
                case "DOCX" -> finish(docx(input));
                case "TXT" -> finish(text(input, false));
                case "MARKDOWN" -> finish(text(input, true));
                default -> throw new IllegalArgumentException("Unsupported type");
            };
        } catch (TextLimitExceeded e) {
            return Result.failed("EXTRACTION_FAILED_FINAL", "TEXT_LIMIT_EXCEEDED");
        }
    }

    private static Result finish(List<Chunk> chunks) {
        long total = chunks.stream().mapToLong(c -> c.text().length()).sum();
        if (total > MAX_TOTAL_CHARS || chunks.size() > MAX_CHUNKS) {
            return Result.failed("EXTRACTION_FAILED_FINAL", "TEXT_LIMIT_EXCEEDED");
        }
        if (total == 0) {
            return Result.failed("UNSUPPORTED_CONTENT", "NO_TEXT");
        }
        return new Result("PROCESSED", null, chunks);
    }

    /** Per-page text; encrypted documents and documents without a text layer are not analysed (no OCR). */
    private static Result pdf(Path input) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(input.toFile()))) {
            if (document.isEncrypted()) {
                return Result.failed("UNSUPPORTED_CONTENT", "ENCRYPTED");
            }
            List<Chunk> chunks = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = document.getNumberOfPages();
            long total = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                total += text.length();
                if (total > MAX_TOTAL_CHARS) {
                    return Result.failed("EXTRACTION_FAILED_FINAL", "TEXT_LIMIT_EXCEEDED");
                }
                for (String part : split(text)) {
                    chunks.add(new Chunk("Page " + page, page, part));
                }
            }
            if (chunks.isEmpty()) {
                return Result.failed("UNSUPPORTED_CONTENT", pages > 0 ? "NO_TEXT_LAYER" : "NO_TEXT");
            }
            return finish(chunks);
        } catch (InvalidPasswordException e) {
            return Result.failed("UNSUPPORTED_CONTENT", "ENCRYPTED");
        }
    }

    /** Paragraph text from word/document.xml with a hardened StAX parser (no DTDs, no external entities). */
    private static List<Chunk> docx(Path input) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        List<Chunk> chunks = new ArrayList<>();
        try (ZipFile zip = new ZipFile(input.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new IOException("Not a word document");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                XMLStreamReader xml = factory.createXMLStreamReader(in);
                StringBuilder paragraph = new StringBuilder();
                StringBuilder section = new StringBuilder();
                String label = null;
                boolean heading = false;
                boolean inText = false;
                long total = 0;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        switch (xml.getLocalName()) {
                            case "p" -> {
                                paragraph.setLength(0);
                                heading = false;
                            }
                            case "pStyle" -> {
                                String style = xml.getAttributeValue(xml.getNamespaceURI(), "val");
                                heading = style != null && (style.startsWith("Heading") || style.equals("Title"));
                            }
                            case "t" -> inText = true;
                            case "tab" -> paragraph.append('\t');
                            case "br" -> paragraph.append('\n');
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                        paragraph.append(xml.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (xml.getLocalName().equals("t")) {
                            inText = false;
                        } else if (xml.getLocalName().equals("p")) {
                            String text = paragraph.toString().strip();
                            total += text.length();
                            if (total > MAX_TOTAL_CHARS) {
                                throw new TextLimitExceeded();
                            }
                            if (text.isEmpty()) {
                                continue;
                            }
                            if (heading) {
                                flush(chunks, label, section);
                                label = text.length() > 200 ? text.substring(0, 200) : text;
                            }
                            section.append(text).append("\n\n");
                        }
                    }
                }
                flush(chunks, label, section);
            }
        }
        return chunks;
    }

    /** TXT and Markdown: strict UTF-8 or UTF-16 with BOM; Markdown headings become section labels. */
    private static List<Chunk> text(Path input, boolean markdown) throws IOException {
        byte[] bytes = Files.readAllBytes(input);
        boolean utf16 = bytes.length >= 2
                && ((bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) || (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE));
        String text;
        try {
            text = (utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid text encoding");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.length() > MAX_TOTAL_CHARS) {
            throw new TextLimitExceeded();
        }
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        String label = null;
        for (String line : text.split("\r?\n", -1)) {
            if (markdown && line.matches("^#{1,6}\\s+.*")) {
                flush(chunks, label, section);
                String heading = line.replaceFirst("^#{1,6}\\s+", "").strip();
                label = heading.length() > 200 ? heading.substring(0, 200) : heading;
            }
            section.append(line).append('\n');
        }
        flush(chunks, label, section);
        return chunks;
    }

    private static void flush(List<Chunk> chunks, String label, StringBuilder section) {
        for (String part : split(section.toString())) {
            chunks.add(new Chunk(label, null, part));
        }
        section.setLength(0);
    }

    /** Splits text into chunks of at most {@link #MAX_CHUNK_CHARS}, preferring paragraph and word boundaries. */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.replace("\r", "").split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            while (p.length() > MAX_CHUNK_CHARS) {
                int cut = p.lastIndexOf(' ', MAX_CHUNK_CHARS);
                if (cut < MAX_CHUNK_CHARS / 2) {
                    cut = MAX_CHUNK_CHARS;
                }
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.add(p.substring(0, cut).strip());
                p = p.substring(cut).strip();
            }
            if (current.length() + p.length() + 2 > MAX_CHUNK_CHARS && current.length() > 0) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        parts.removeIf(String::isBlank);
        return parts;
    }

    private static void write(Result result, Path out) throws IOException, NoSuchAlgorithmException {
        Result checked = result.outcome().equals("PROCESSED") ? finish(result.chunks()) : result;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long chars = 0;
        try (OutputStream file = Files.newOutputStream(out.resolve("chunks.jsonl"));
             BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(new DigestOutputStream(file, sha),
                     StandardCharsets.UTF_8))) {
            int ordinal = 0;
            for (Chunk chunk : checked.outcome().equals("PROCESSED") ? checked.chunks() : List.<Chunk>of()) {
                ordinal++;
                chars += chunk.text().length();
                writer.write("{\"ordinal\":" + ordinal + ",\"label\":" + json(chunk.label()) + ",\"page\":"
                        + (chunk.page() == null ? "null" : chunk.page()) + ",\"text\":" + json(chunk.text()) + "}\n");
            }
        }
        int count = checked.outcome().equals("PROCESSED") ? checked.chunks().size() : 0;
        String manifest = "{\"outcome\":" + json(checked.outcome()) + ",\"reason\":" + json(checked.reason())
                + ",\"chunks\":" + count + ",\"chars\":" + chars + ",\"sha256\":\""
                + HexFormat.of().formatHex(sha.digest()) + "\"}";
        Files.writeString(out.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
    }

    static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
