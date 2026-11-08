import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { ChangeRequest } from '../api/types';
import { ScopeDiffTable } from '../components/ScopeDiffTable';

const ERRORS: Record<string, string> = {
  SELF_APPROVAL_FORBIDDEN: 'You submitted this change; another Client Approver must decide.',
  CHANGE_REQUEST_STATE: 'This change request no longer awaits a decision.',
  FORBIDDEN: 'Only a Client Approver can decide on changes.',
};

/**
 * Client Approver decisions on change requests (C-09). Shows only the change request, its assessment and the scope
 * diff, never the rest of the requirement (C-18). The server re-checks role, state and self-approval.
 */
export function ChangeDecisionsPage() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['change-requests'], queryFn: () => api.get<ChangeRequest[]>('/api/v1/change-requests') });
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

  async function decide(id: string, decision: 'ACCEPT' | 'DECLINE') {
    setMessage(null);
    try {
      await api.post(`/api/v1/change-requests/${id}/decision`, { decision, note: notes[id] ?? '' });
      setMessage(decision === 'ACCEPT' ? 'Accepted. A new approved scope version is in place and a new analysis starts.' : 'Declined.');
      await queryClient.invalidateQueries({ queryKey: ['change-requests'] });
    } catch (e) {
      setMessage(e instanceof ApiError ? (ERRORS[e.code] ?? e.message) : 'The decision failed.');
    }
  }

  return (
    <section>
      <h1>Change decisions</h1>
      {list.isPending && <p>Loading…</p>}
      {list.isError && <p role="alert">The change requests could not be loaded.</p>}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      {list.data?.length === 0 && <p>No change request awaits your decision.</p>}
      {list.data?.map((c) => (
        <article key={c.id} className="card" aria-label={`Change request ${c.requestedChange}`}>
          <h2>{c.requestedChange}</h2>
          <p className="small">
            {c.status} · against approved scope v{c.baselineVersion}
          </p>
          <p>
            <strong>Justification:</strong> {c.justification}
          </p>
          <p>
            <strong>Expected impact:</strong> {c.expectedImpact}
          </p>
          {c.scopeAssessment && (
            <p>
              <strong>Assessment:</strong> {c.scopeAssessment} · effort {c.effortEstimate} · timeline {c.timelineImpact} ·
              technical impact {c.technicalImpact}
            </p>
          )}
          <ScopeDiffTable diff={c.diff} />
          {c.status === 'AWAITING_CLIENT_DECISION' && (
            <div className="actions">
              <label>
                Note (required to decline)
                <textarea
                  value={notes[c.id] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [c.id]: e.target.value })}
                  rows={2}
                  maxLength={2000}
                />
              </label>
              <button type="button" onClick={() => void decide(c.id, 'ACCEPT')}>
                Accept change
              </button>
              <button type="button" disabled={(notes[c.id] ?? '').trim().length < 10} onClick={() => void decide(c.id, 'DECLINE')}>
                Decline change
              </button>
            </div>
          )}
        </article>
      ))}
    </section>
  );
}
