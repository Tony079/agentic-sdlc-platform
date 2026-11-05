import { useState, type ChangeEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, uploadFile } from '../api/client';
import type { DocumentItem, DocumentList, DocumentState, RequirementState } from '../api/types';

/** User-facing labels for the PRD 6C states (exact wording is a UI choice, PRD J10). */
const STATE_LABELS: Record<DocumentState, string> = {
  UPLOADING: 'Uploading',
  QUARANTINED: 'Waiting for security scan',
  VALIDATING: 'Validating',
  SCANNING: 'Scanning',
  READY_FOR_EXTRACTION: 'Scanned, ready for processing',
  EXTRACTING: 'Processing',
  PROCESSED: 'Processed',
  EXTRACTION_FAILED: 'Processing failed, retrying',
  EXTRACTION_FAILED_FINAL: 'Could not be processed',
  UNSUPPORTED_CONTENT: 'Not analysed (scanned or image-only content; OCR is not supported)',
  REJECTED_INVALID: 'Rejected',
  REJECTED_MALWARE: 'Rejected: security threat detected',
  UPLOAD_FAILED: 'Upload failed',
  SUPERSEDED: 'Replaced',
  DELETED: 'Deleted',
};

const REASONS: Record<string, string> = {
  BINARY_CONTENT: 'The file is not a text document.',
  CONTENT_TYPE_MISMATCH: 'The content does not match the file type.',
  CORRUPT_PDF: 'The PDF is damaged.',
  CORRUPT_DOCX: 'The Word document is damaged.',
  ARCHIVE_LIMITS_EXCEEDED: 'The Word document is too large when unpacked.',
  UNSAFE_ARCHIVE_ENTRY: 'The Word document contains unsafe content.',
  MACROS_NOT_ALLOWED: 'Documents with macros are not accepted.',
  INVALID_TEXT_ENCODING: 'The text is not valid UTF-8 or UTF-16.',
  MALWARE_DETECTED: 'The file was blocked by the malware scan.',
};

const UPLOAD_ERRORS: Record<string, string> = {
  UNSUPPORTED_FILE_TYPE: 'Only PDF, DOCX, TXT and Markdown files are accepted.',
  CONTENT_TYPE_MISMATCH: 'The file type does not match its name.',
  EMPTY_FILE: 'The file is empty.',
  TOO_MANY_FILES: 'This requirement already has the maximum number of attachments.',
  TOTAL_SIZE_EXCEEDED: 'The attachments of this requirement would exceed the total size limit.',
  INVALID_FILE_NAME: 'The file name is not valid.',
};

/** Non-terminal states in which attachments may be added (server decides; this only hides the control). */
const UPLOADABLE: RequirementState[] = ['DRAFT', 'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING'];
const IN_PROGRESS: DocumentState[] = ['UPLOADING', 'QUARANTINED', 'VALIDATING', 'SCANNING', 'EXTRACTING', 'EXTRACTION_FAILED'];

function uploadMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'FILE_TOO_LARGE') return 'The file is larger than 20 MB.';
    if (error.code === 'DUPLICATE_DOCUMENT') return 'This file is already attached.';
    if (error.code === 'DOCUMENTS_LOCKED') return 'Attachments can no longer be added to this requirement.';
    const field = error.details.find((d) => d.field === 'file');
    const message = field ? UPLOAD_ERRORS[field.code] : undefined;
    if (message) return message;
  }
  return 'The upload failed.';
}

function size(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

interface Upload {
  name: string;
  percent: number;
  error: string | null;
}

/** Attachments of one requirement (PRD J10, 7A.1): upload with progress, processing status, download, removal. */
export function DocumentsPanel({ requirementId, requirementState }: { requirementId: string; requirementState: RequirementState }) {
  const queryClient = useQueryClient();
  const base = `/api/v1/requirements/${requirementId}/documents`;
  const [uploads, setUploads] = useState<Upload[]>([]);
  const [error, setError] = useState<string | null>(null);
  const documents = useQuery({
    queryKey: ['documents', requirementId],
    queryFn: () => api.get<DocumentList>(base),
    refetchInterval: (query) =>
      (query.state.data?.items ?? []).some((d) => IN_PROGRESS.includes(d.state)) ? 3000 : false,
  });

  async function onFiles(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';
    for (const file of files) {
      setUploads((current) => [...current, { name: file.name, percent: 0, error: null }]);
      const update = (patch: Partial<Upload>) =>
        setUploads((current) => current.map((u) => (u.name === file.name ? { ...u, ...patch } : u)));
      try {
        await uploadFile<DocumentItem>(base, file, (percent) => update({ percent }));
        setUploads((current) => current.filter((u) => u.name !== file.name));
      } catch (e) {
        update({ error: uploadMessage(e) });
      }
    }
    await queryClient.invalidateQueries({ queryKey: ['documents', requirementId] });
  }

  async function remove(document: DocumentItem) {
    if (!window.confirm(`Remove ${document.fileName}?`)) return;
    setError(null);
    try {
      await api.delete(`${base}/${document.documentId}`);
      await queryClient.invalidateQueries({ queryKey: ['documents', requirementId] });
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'DOCUMENTS_LOCKED'
        ? 'Attachments can only be removed before submission.'
        : 'The attachment could not be removed.');
    }
  }

  return (
    <section aria-labelledby="documents-heading">
      <h2 id="documents-heading">Attachments</h2>
      <p className="hint">
        PDF, DOCX, TXT or Markdown; up to 10 files, 20 MB each. Files are scanned for malware before anyone can open them.
      </p>
      {UPLOADABLE.includes(requirementState) && (
        <label className="upload">
          Add files
          <input type="file" multiple accept=".pdf,.docx,.txt,.md,.markdown" onChange={(e) => void onFiles(e)} />
        </label>
      )}
      {uploads.map((u) => (
        <p key={u.name} className={u.error ? 'error' : undefined} role={u.error ? 'alert' : 'status'}>
          {u.name}: {u.error ?? `uploading ${u.percent}%`}
        </p>
      ))}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {documents.isError && <p className="error">The attachments could not be loaded.</p>}
      <ul className="documents">
        {(documents.data?.items ?? []).map((d) => (
          <li key={d.documentId}>
            {d.downloadable ? <a href={`${base}/${d.documentId}/content`}>{d.fileName}</a> : <span>{d.fileName}</span>} ·{' '}
            {size(d.sizeBytes)} · <span className={`doc-state doc-${d.state.toLowerCase()}`}>{STATE_LABELS[d.state]}</span>
            {d.reasonCode && REASONS[d.reasonCode] && <span className="hint"> ({REASONS[d.reasonCode]})</span>}
            {requirementState === 'DRAFT' && (
              <button type="button" onClick={() => void remove(d)}>
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>
      {documents.data && (documents.data.items ?? []).length === 0 && <p>No attachments yet.</p>}
    </section>
  );
}
