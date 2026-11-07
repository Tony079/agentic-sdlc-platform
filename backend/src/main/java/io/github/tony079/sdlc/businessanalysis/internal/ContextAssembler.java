package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.PhaseA;
import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.Inventory;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.SymbolInfo;

/**
 * Builds the untrusted data blocks of the BA agent (TDD 12.5, 12.7): every block is data, labelled with a trusted
 * source reference, sent separately from the fixed instructions, and bounded in size. File excerpts carry line numbers
 * so that citations can be checked against the pinned snapshot.
 */
final class ContextAssembler {

    static final int MAX_FILES_LISTED = 2000;
    static final int MAX_SYMBOLS = 3000;
    static final int MAX_FILE_CHARS = 64 * 1024;
    static final int MAX_TOTAL_FILE_CHARS = 256 * 1024;
    static final int MAX_DOCUMENT_CHARS = 120 * 1024;
    static final int MAX_RELEVANT_FILES = 15;

    /** One analysed document chunk with its trusted reference. */
    record DocumentChunk(String versionId, int ordinal, String fileName, String label, String text) {
    }

    private ContextAssembler() {
    }

    static DataBlock requirement(Content content) {
        StringBuilder text = new StringBuilder();
        append(text, "Title", content.title());
        append(text, "Description", content.description());
        append(text, "Business objective", content.businessObjective());
        append(text, "Expected outcomes", content.expectedOutcomes());
        append(text, "Acceptance criteria", content.acceptanceCriteria());
        append(text, "Constraints", content.constraints());
        append(text, "Integrations", content.integrations());
        return new DataBlock("requirement", text.toString());
    }

    static List<DataBlock> inventory(Inventory inventory) {
        StringBuilder files = new StringBuilder();
        inventory.files().stream().limit(MAX_FILES_LISTED).forEach(f -> files.append(f.path()).append('\t')
                .append(f.language() == null ? "-" : f.language()).append('\t').append(f.sizeBytes()).append('\n'));
        StringBuilder symbols = new StringBuilder();
        inventory.symbols().stream().limit(MAX_SYMBOLS).forEach(s -> symbols.append(symbol(s)).append('\n'));
        StringBuilder deps = new StringBuilder();
        inventory.dependencies().forEach(d -> deps.append(d.path()).append('\t').append(d.buildTool()).append('\t')
                .append(d.groupId() == null ? "" : d.groupId()).append(':').append(d.artifactId()).append(':')
                .append(d.version() == null ? "" : d.version()).append('\n'));
        return List.of(new DataBlock("repository:files (path, language, bytes)", files.toString()),
                new DataBlock("repository:symbols (path:lines kind name [stereotype] [http])", symbols.toString()),
                new DataBlock("repository:dependencies", deps.toString()));
    }

    static String symbol(SymbolInfo s) {
        StringBuilder line = new StringBuilder(s.path()).append(':').append(s.startLine()).append('-').append(s.endLine())
                .append(' ').append(s.kind()).append(' ');
        if (s.packageName() != null) {
            line.append(s.packageName()).append('.');
        }
        line.append(s.typeName());
        if (s.memberName() != null) {
            line.append('#').append(s.memberName());
        }
        if (s.stereotype() != null) {
            line.append(" [").append(s.stereotype()).append(']');
        }
        if (s.httpMethod() != null) {
            line.append(" [").append(s.httpMethod()).append(' ').append(s.httpPath() == null ? "" : s.httpPath()).append(']');
        }
        return line.toString();
    }

    static List<DataBlock> documents(List<DocumentChunk> chunks) {
        List<DataBlock> blocks = new ArrayList<>();
        int total = 0;
        for (DocumentChunk chunk : chunks) {
            if (total + chunk.text().length() > MAX_DOCUMENT_CHARS) {
                break;
            }
            total += chunk.text().length();
            blocks.add(new DataBlock("document documentVersionId=" + chunk.versionId() + " chunk=" + chunk.ordinal()
                    + " file=" + chunk.fileName() + (chunk.label() == null ? "" : " section=" + chunk.label()),
                    chunk.text()));
        }
        return blocks;
    }

    /** The model's relevant paths restricted to real snapshot files; a deterministic fallback when it names none. */
    static Set<String> relevantPaths(PhaseA phaseA, Inventory inventory) {
        Set<String> known = new LinkedHashSet<>();
        inventory.files().forEach(f -> known.add(f.path()));
        Set<String> chosen = new LinkedHashSet<>();
        for (String path : phaseA.relevantPaths()) {
            if (known.contains(path) && chosen.size() < MAX_RELEVANT_FILES) {
                chosen.add(path);
            }
        }
        if (chosen.isEmpty()) {
            for (SymbolInfo s : inventory.symbols()) {
                if (s.stereotype() != null && chosen.size() < MAX_RELEVANT_FILES) {
                    chosen.add(s.path());
                }
            }
        }
        return chosen;
    }

    static List<DataBlock> fileExcerpts(Map<String, String> files, Set<String> order) {
        List<DataBlock> blocks = new ArrayList<>();
        int total = 0;
        for (String path : order) {
            String text = files.get(path);
            if (text == null) {
                continue;
            }
            StringBuilder numbered = new StringBuilder();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length && numbered.length() < MAX_FILE_CHARS; i++) {
                if (i == lines.length - 1 && lines[i].isEmpty()) {
                    break;
                }
                numbered.append(i + 1).append("| ").append(lines[i]).append('\n');
            }
            if (total + numbered.length() > MAX_TOTAL_FILE_CHARS) {
                break;
            }
            total += numbered.length();
            blocks.add(new DataBlock("repository-file path=" + path + " (line-numbered)", numbered.toString()));
        }
        return blocks;
    }

    static DataBlock phaseASummary(PhaseA phaseA) {
        StringBuilder text = new StringBuilder("Understanding: ").append(phaseA.understanding()).append('\n');
        phaseA.components().forEach(c -> text.append("Component: ").append(c.name()).append(" -> ")
                .append(String.join(", ", c.paths())).append('\n'));
        return new DataBlock("phase-a summary (model output, untrusted)", text.toString());
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value).append('\n');
        }
    }
}
