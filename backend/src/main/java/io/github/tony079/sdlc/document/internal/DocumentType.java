package io.github.tony079.sdlc.document.internal;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The four supported formats (PRD 7A). Everything else, including archives and executables, is rejected. */
enum DocumentType {
    PDF(Set.of("pdf"), Set.of("application/pdf")),
    DOCX(Set.of("docx"), Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    TXT(Set.of("txt"), Set.of("text/plain")),
    MARKDOWN(Set.of("md", "markdown"), Set.of("text/markdown", "text/x-markdown", "text/plain"));

    /** Browsers often send this for any file; it says nothing, so it is accepted and the content decides. */
    private static final String GENERIC = "application/octet-stream";

    private final Set<String> extensions;
    private final Set<String> mimeTypes;

    DocumentType(Set<String> extensions, Set<String> mimeTypes) {
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
    }

    static Optional<DocumentType> byFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (DocumentType type : values()) {
            if (type.extensions.contains(extension)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Whether a declared MIME type (from the upload) is consistent with this type. Parameters are ignored. */
    boolean acceptsDeclaredMime(String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        String base = declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return base.equals(GENERIC) || mimeTypes.contains(base);
    }
}
