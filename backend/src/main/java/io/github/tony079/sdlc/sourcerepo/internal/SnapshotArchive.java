package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Streams a GitHub tarball (gzip + tar) without writing anything to disk (TDD 14.5, D-M4R-08). Enforces the entry,
 * expanded-size and expansion-ratio limits on bytes actually read (headers are not trusted), checks the pax global
 * header's commit SHA, rejects unsafe paths and skips links and special files. Client content is only hashed and, for
 * indexable files, handed to a visitor; it is never executed.
 */
final class SnapshotArchive {

    static final int MAX_ENTRIES = 50_000;
    static final long MAX_EXPANDED_BYTES = 1L << 30;
    static final long MAX_RATIO = 100;
    static final long MAX_INDEXED_FILE_BYTES = 1_048_576;
    static final int MAX_PATH = 1024;

    /** A deterministic limit or integrity violation: the snapshot fails with this code and is not retried. */
    static final class SnapshotRejected extends Exception {
        private final String code;

        SnapshotRejected(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record FileEntry(String path, String language, long size, String sha256, boolean indexable, String skipReason) {
    }

    record Inventory(List<FileEntry> files, int rejectedCount, long expandedBytes) {
        int skippedCount() {
            return rejectedCount + (int) files.stream().filter(f -> !f.indexable()).count();
        }
    }

    /** Receives the content of indexable files (at most 1 MB each). */
    interface Visitor {
        void file(String path, String language, byte[] content);
    }

    private SnapshotArchive() {
    }

    /** Archive limits (TDD 14.5); tests use smaller values. */
    record Limits(int maxEntries, long maxExpandedBytes, long maxRatio) {
        static final Limits DEFAULT = new Limits(MAX_ENTRIES, MAX_EXPANDED_BYTES, MAX_RATIO);
    }

    static Inventory read(InputStream gzip, long compressedBytes, String expectedSha, Visitor visitor)
            throws IOException, SnapshotRejected {
        return read(gzip, compressedBytes, expectedSha, visitor, Limits.DEFAULT);
    }

    static Inventory read(InputStream gzip, long compressedBytes, String expectedSha, Visitor visitor, Limits limits)
            throws IOException, SnapshotRejected {
        return read(gzip, compressedBytes, expectedSha, visitor, limits, null);
    }

    /**
     * As above; {@code contentFilter}, when given, selects the paths whose content (at most 1 MB) is handed to the
     * visitor instead of the indexable types. Inventory classification is unchanged.
     */
    static Inventory read(InputStream gzip, long compressedBytes, String expectedSha, Visitor visitor, Limits limits,
                          java.util.function.Predicate<String> contentFilter) throws IOException, SnapshotRejected {
        List<FileEntry> files = new ArrayList<>();
        int rejected = 0;
        int entries = 0;
        long expanded = 0;
        long ratioLimit = Math.max(1, compressedBytes) * limits.maxRatio();
        boolean shaChecked = false;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(gzip))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!shaChecked) {
                    // GitHub writes the commit SHA as the pax global header "comment"; the reader applies global
                    // headers to the following entries, where unknown keys appear as extra pax headers.
                    String comment = entry.getExtraPaxHeader("comment");
                    if (comment == null || !comment.strip().equals(expectedSha)) {
                        throw new SnapshotRejected("SNAPSHOT_SHA_MISMATCH");
                    }
                    shaChecked = true;
                }
                if (++entries > limits.maxEntries()) {
                    throw new SnapshotRejected("SNAPSHOT_TOO_MANY_FILES");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String path = relativePath(entry.getName());
                if (path == null) {
                    rejected++;
                    continue;
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    files.add(new FileEntry(path, null, 0, null, false, "LINK"));
                    continue;
                }
                if (!isRegularFile(entry)) {
                    files.add(new FileEntry(path, null, 0, null, false, "SPECIAL_FILE"));
                    continue;
                }
                String language = language(path);
                boolean indexedType = isIndexedType(path);
                boolean wanted = contentFilter == null ? indexedType : contentFilter.test(path);
                MessageDigest digest = sha256();
                byte[] buffer = new byte[8192];
                java.io.ByteArrayOutputStream kept = wanted ? new java.io.ByteArrayOutputStream() : null;
                long size = 0;
                int read;
                while ((read = tar.read(buffer)) != -1) {
                    size += read;
                    expanded += read;
                    if (expanded > limits.maxExpandedBytes()) {
                        throw new SnapshotRejected("SNAPSHOT_EXPANDED_TOO_LARGE");
                    }
                    if (expanded > ratioLimit) {
                        throw new SnapshotRejected("SNAPSHOT_RATIO_EXCEEDED");
                    }
                    digest.update(buffer, 0, read);
                    if (kept != null) {
                        if (size > MAX_INDEXED_FILE_BYTES) {
                            kept = null;
                        } else {
                            kept.write(buffer, 0, read);
                        }
                    }
                }
                String sha = HexFormat.of().formatHex(digest.digest());
                String skip = !indexedType ? "NOT_INDEXED_TYPE" : size > MAX_INDEXED_FILE_BYTES ? "TOO_LARGE" : null;
                files.add(new FileEntry(path, language, size, sha, skip == null, skip));
                if (wanted && kept != null && size <= MAX_INDEXED_FILE_BYTES && visitor != null) {
                    visitor.file(path, language, kept.toByteArray());
                }
            }
        } catch (IOException e) {
            if (e.getCause() instanceof SnapshotRejected rejectedCause) {
                throw rejectedCause;
            }
            throw new SnapshotRejected("SNAPSHOT_CORRUPT");
        } catch (IllegalArgumentException e) {
            throw new SnapshotRejected("SNAPSHOT_CORRUPT");
        }
        if (!shaChecked) {
            throw new SnapshotRejected("SNAPSHOT_SHA_MISMATCH");
        }
        return new Inventory(files, rejected, expanded);
    }

    /**
     * The path inside the repository: GitHub's top-level directory removed. Null (rejected) for absolute paths, "..",
     * empty or "." segments, backslashes, control characters or NUL, and paths over 1024 characters.
     */
    static String relativePath(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20 || name.charAt(i) == 0x7f) {
                return null;
            }
        }
        String[] parts = name.split("/", -1);
        if (parts.length < 2) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() && i == parts.length - 1) {
                break;
            }
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        if (parts[0].equals("..") || parts[0].equals(".") || out.length() == 0 || out.length() > MAX_PATH) {
            return null;
        }
        return out.toString();
    }

    /** Only plain file types; FIFOs, devices, sparse and vendor types are skipped (isFile() is too permissive). */
    private static boolean isRegularFile(TarArchiveEntry entry) {
        byte flag = entry.getLinkFlag();
        return !entry.isFIFO() && !entry.isCharacterDevice() && !entry.isBlockDevice()
                && (flag == org.apache.commons.compress.archivers.tar.TarConstants.LF_NORMAL
                || flag == org.apache.commons.compress.archivers.tar.TarConstants.LF_OLDNORM
                || flag == org.apache.commons.compress.archivers.tar.TarConstants.LF_CONTIG);
    }

    static boolean isIndexedType(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") || file.equals("pom.xml") || file.equals("build.gradle")
                || file.equals("build.gradle.kts");
    }

    static String language(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (file.equals("pom.xml")) {
            return "MAVEN";
        }
        if (file.equals("build.gradle") || file.equals("build.gradle.kts")) {
            return "GRADLE";
        }
        int dot = file.lastIndexOf('.');
        String ext = dot < 0 ? "" : file.substring(dot + 1);
        return switch (ext) {
            case "java" -> "JAVA";
            case "kt", "kts" -> "KOTLIN";
            case "xml" -> "XML";
            case "js", "mjs", "cjs" -> "JAVASCRIPT";
            case "ts", "tsx" -> "TYPESCRIPT";
            case "py" -> "PYTHON";
            case "go" -> "GO";
            case "sql" -> "SQL";
            case "yml", "yaml" -> "YAML";
            case "json" -> "JSON";
            case "properties" -> "PROPERTIES";
            case "md" -> "MARKDOWN";
            case "html", "htm" -> "HTML";
            case "css", "scss" -> "CSS";
            case "gradle" -> "GRADLE";
            default -> null;
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
