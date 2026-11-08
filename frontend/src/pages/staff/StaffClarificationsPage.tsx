import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Clarification, PendingDocumentDecision } from '../../api/types';
import { ClarificationCard } from '../../components/ClarificationCard';

/**
 * Business Reviewer inbox (FR-UI-02): clarifications assigned to reviewers, and documents that need a decision
 * (T12/T13: exclude a failed or unsupported document so analysis can continue without it).
 */
export function StaffClarificationsPage() {
  const queryClient = useQueryClient();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const inbox = useQuery({
    queryKey: ['staff-clarifications'],
    queryFn: () => api.get<Clarification[]>('/api/v1/staff/clarifications'),
  });
  const decisions = useQuery({
    queryKey: ['staff-document-decisions'],
    queryFn: () => api.get<PendingDocumentDecision[]>('/api/v1/staff/document-decisions'),
  });

  async function exclude(d: PendingDocumentDecision) {
    setMessage(null);
    try {
      await api.post('/api/v1/staff/document-decisions', { versionId: d.versionId, reason: reasons[d.versionId] });
      setMessage(`${d.fileName} is excluded; it will be listed on the BRD.`);
      await queryClient.invalidateQueries({ queryKey: ['staff-document-decisions'] });
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : 'The decision failed.');
    }
  }

  return (
    <section>
      <p>
        <Link to="/staff">Staff workspace</Link>
      </p>
      <h1>Reviewer inbox</h1>
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      <h2>Documents needing a decision</h2>
      {decisions.isError && <p role="alert">The document decisions could not be loaded.</p>}
      {decisions.data?.length === 0 && <p>No document needs a decision.</p>}
      <ul>
        {decisions.data?.map((d) => (
          <li key={d.versionId}>
            {d.requirementKey}: <code>{d.fileName}</code> · {d.state}
            {d.reasonCode && ` (${d.reasonCode})`}
            <label>
              Reason for exclusion
              <input
                value={reasons[d.versionId] ?? ''}
                maxLength={1000}
                onChange={(e) => setReasons({ ...reasons, [d.versionId]: e.target.value })}
              />
            </label>
            <button type="button" disabled={!reasons[d.versionId]?.trim()} onClick={() => void exclude(d)}>
              Exclude document
            </button>
          </li>
        ))}
      </ul>
      <h2>Clarifications</h2>
      {inbox.isError && <p role="alert">The clarifications could not be loaded.</p>}
      {inbox.data?.length === 0 && <p>No questions are waiting.</p>}
      {inbox.data?.map((c) => (
        <ClarificationCard
          key={c.id}
          clarification={c}
          basePath="/api/v1/staff/clarifications"
          staffActions
          onChanged={() => void queryClient.invalidateQueries({ queryKey: ['staff-clarifications'] })}
        />
      ))}
    </section>
  );
}
