import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { ClientBrd, RequirementState } from '../api/types';
import { BrdItems } from './BrdItems';

const BEFORE_RELEASE: Partial<Record<RequirementState, string>> = {
  ANALYSING: 'The analysis is running.',
  BRD_DRAFTED: 'A BRD draft is being prepared for internal review.',
  INTERNAL_REVIEW: 'A BRD draft is in internal review.',
  REVISION_REQUESTED: 'The internal reviewer requested a revision; the analysis will run again.',
  INTERNAL_APPROVED: 'The BRD was approved internally and is being released to you.',
  CLIENT_CHANGES_REQUESTED: 'Your change request is being analysed.',
};

/** Client view of the released BRD (TDD 19.5). The server returns 404 until release (AC-CORE-08). */
export function BrdPanel({ requirementId, requirementState }: { requirementId: string; requirementState: RequirementState }) {
  const queryClient = useQueryClient();
  const brd = useQuery({
    queryKey: ['brd', requirementId, requirementState],
    queryFn: async () => {
      try {
        return await api.get<ClientBrd>(`/api/v1/requirements/${requirementId}/brd`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });
  const [text, setText] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function requestChanges(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await api.post(`/api/v1/requirements/${requirementId}/client-changes`, { text: text.trim() });
      setText('');
      setMessage('Your change request was submitted. It is assessed and reviewed, and a Client Approver decides; nothing changes until it is accepted.');
      await queryClient.invalidateQueries();
    } catch {
      setMessage('The change request could not be sent.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Business requirements document">
      <h2>Business requirements document</h2>
      {brd.isPending && <p>Loading…</p>}
      {!brd.isPending && !brd.data?.version?.items && (
        <p>{BEFORE_RELEASE[requirementState] ?? 'No BRD has been shared with you yet.'}</p>
      )}
      {brd.data?.version?.items && (
        <>
          <p>
            Version {brd.data.version.versionNo} · {brd.data.version.status === 'RELEASED' ? 'Released' : brd.data.version.status}{' '}
            · <span className="small">content hash {brd.data.version.contentHash.substring(0, 12)}</span>
          </p>
          {brd.data.version.understanding && <p>{brd.data.version.understanding}</p>}
          <BrdItems items={brd.data.version.items} />
          {requirementState === 'SHARED_WITH_CLIENT' && (
            <form onSubmit={requestChanges} className="cancel-form">
              <label>
                Request changes (10 to 5000 characters)
                <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={3} />
              </label>
              <button type="submit" disabled={busy || text.trim().length < 10}>
                Request changes
              </button>
            </form>
          )}
        </>
      )}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
    </section>
  );
}
