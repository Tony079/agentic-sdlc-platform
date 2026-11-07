package io.github.tony079.sdlc.businessanalysis.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.document.api.DocumentProcessing.AnalysisDocument;
import io.github.tony079.sdlc.requirement.api.RequirementInputs;
import io.github.tony079.sdlc.requirement.api.RequirementInputs.RevisionInput;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.PinnedRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guard 9.4 and the frozen input set (TDD 9.4, 12.3). The input hash covers the requirement revision, the pinned
 * repository commit and snapshot, every current document version with its state and extraction, and reviewer
 * correction notes. The commit step recomputes it from the current data; any difference makes the result stale.
 */
@Component
@org.springframework.context.annotation.Profile("!migrate & !provision")
@Transactional(propagation = Propagation.MANDATORY)
class InputSets {

    /** Not yet decided by scanning/extraction: analysis must wait (guard 9.4 c). */
    static final Set<String> IN_PROGRESS = Set.of("UPLOADING", "QUARANTINED", "VALIDATING", "SCANNING",
            "READY_FOR_EXTRACTION", "EXTRACTING", "EXTRACTION_FAILED");
    /** Needs a Business Reviewer decision or client action (guard 9.4 b); no decision path exists yet: wait. */
    static final Set<String> NEEDS_DECISION = Set.of("UNSUPPORTED_CONTENT", "EXTRACTION_FAILED_FINAL", "UPLOAD_FAILED");
    /** Terminal and excluded from analysis, listed in the BRD (AC-DOC-12). */
    static final Set<String> EXCLUDED = Set.of("REJECTED_INVALID", "REJECTED_MALWARE");

    record DocumentEntry(UUID documentId, UUID versionId, String fileName, String state, Integer extractionNo,
                         String outputSha256, boolean included) {
    }

    record Draft(UUID requirementId, RevisionInput revision, PinnedRepository repository, List<DocumentEntry> documents,
                 String correctionsJson, String inputHash) {
    }

    private final RequirementInputs requirements;
    private final RepositoryInputs repositories;
    private final DocumentProcessing documents;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    InputSets(RequirementInputs requirements, RepositoryInputs repositories, DocumentProcessing documents,
              org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.requirements = requirements;
        this.repositories = repositories;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    /** The jsonb text form of the corrections, exactly as stored, so that hashes recomputed later match. */
    String normaliseCorrections(String correctionsJson) {
        if (correctionsJson == null || correctionsJson.isBlank()) {
            return "[]";
        }
        return jdbc.sql("SELECT CAST(:c AS jsonb)::text").param("c", correctionsJson).query(String.class).single();
    }

    /** The input set if guard 9.4 holds now; empty otherwise. */
    Optional<Draft> evaluate(UUID requirementId, String correctionsJson) {
        Optional<RevisionInput> revision = requirements.currentRevision(requirementId);
        Optional<PinnedRepository> repository = repositories.pinned(requirementId)
                .filter(r -> "INDEXED".equals(r.snapshotStatus()));
        if (revision.isEmpty() || repository.isEmpty()) {
            return Optional.empty();
        }
        List<DocumentEntry> entries = new ArrayList<>();
        for (AnalysisDocument doc : documents.analysisDocuments(requirementId)) {
            if (IN_PROGRESS.contains(doc.state()) || NEEDS_DECISION.contains(doc.state())) {
                return Optional.empty();
            }
            boolean processed = "PROCESSED".equals(doc.state()) && doc.extractionNo() != null;
            if (!processed && !EXCLUDED.contains(doc.state())) {
                return Optional.empty();
            }
            entries.add(new DocumentEntry(doc.documentId(), doc.versionId(), doc.fileName(), doc.state(),
                    processed ? doc.extractionNo() : null, processed ? doc.outputSha256() : null, processed));
        }
        entries.sort(Comparator.comparing(e -> e.versionId().toString()));
        String corrections = normaliseCorrections(correctionsJson);
        return Optional.of(new Draft(requirementId, revision.get(), repository.get(), List.copyOf(entries), corrections,
                hash(revision.get(), repository.get(), entries, corrections)));
    }

    static String hash(RevisionInput revision, PinnedRepository repository, List<DocumentEntry> documents,
                       String corrections) {
        StringBuilder canonical = new StringBuilder("input-set:v1\n");
        canonical.append("revision:").append(revision.revisionNo()).append(':').append(revision.contentHash()).append('\n');
        canonical.append("repository:").append(repository.repositoryId()).append(':').append(repository.commitSha())
                .append(':').append(repository.snapshotId()).append('\n');
        for (DocumentEntry doc : documents) {
            canonical.append("document:").append(doc.versionId()).append(':').append(doc.state()).append(':')
                    .append(doc.extractionNo()).append(':').append(doc.outputSha256()).append('\n');
        }
        canonical.append("corrections:").append(corrections);
        return sha256(canonical.toString());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
