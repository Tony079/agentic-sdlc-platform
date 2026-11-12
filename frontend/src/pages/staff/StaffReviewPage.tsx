import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { ReviewDetail } from '../../api/types';
import { BrdItems } from '../../components/BrdItems';
import { MockOutputNotice } from '../../components/MockNotice';
import { VersionComparison } from '../../components/VersionComparison';

const ERRORS: Record<string, string> = {
  VERSION_CHANGED: 'This BRD version changed. Reload before deciding.',
  STALE_INPUTS: 'The inputs of this version changed; it cannot be approved.',
  FLAGS_NOT_ACKNOWLEDGED: 'Acknowledge every flagged statement first.',
  SELF_APPROVAL_FORBIDDEN: 'You wrote correction notes for this version; another reviewer must approve it.',
  APPROVAL_CONFLICT: 'This version was already decided.',
  OPEN_CLARIFICATIONS: 'A blocking clarification is still open for this requirement.',
  FORBIDDEN: 'You are not allowed to review BRDs for this organisation.',
};

/** Business Reviewer decision page (TDD 19.3): acknowledge flags, approve (bound to the shown hash), revise, reject. */
export function StaffReviewPage() {
  const { versionId } = useParams();
  const queryClient = useQueryClient();
  const detail = useQuery({
    queryKey: ['review', versionId],
    queryFn: () => api.get<ReviewDetail>(`/api/v1/staff/brd-versions/${versionId}`),
  });
  const [notes, setNotes] = useState('');
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage(success);
      await queryClient.invalidateQueries({ queryKey: ['review', versionId] });
    } catch (e) {
      setMessage(e instanceof ApiError ? (ERRORS[e.code] ?? e.message) : 'The action failed.');
    } finally {
      setBusy(false);
    }
  }

  if (detail.isPending) return <p>Loading…</p>;
  if (!detail.data) return <p role="alert">This BRD version is not available.</p>;
  const { version, requirement, requirementKey, state } = detail.data;
  const open = version.status === 'IN_REVIEW' && state === 'INTERNAL_REVIEW';

  return (
    <section>
      <p>
        <Link to="/staff">Staff workspace</Link>
      </p>
      <h1>
        {requirementKey}: {requirement.title}
      </h1>
      <p>
        BRD version {version.versionNo} · {version.status} · requirement state {state} · model {version.modelProvider}/
        {version.modelId} · <span className="small">content hash {version.contentHash.substring(0, 12)}</span>
      </p>
      <MockOutputNotice provider={version.modelProvider} />
      {requirement.description && <p>{requirement.description}</p>}
      {version.understanding && (
        <p>
          <strong>Agent's understanding:</strong> {version.understanding}
        </p>
      )}
      <BrdItems
        items={version.items}
        renderActions={(item) =>
          open && item.supportState === 'FLAGGED' && !item.acknowledged ? (
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                void act(
                  () => api.post(`/api/v1/staff/brd-versions/${version.id}/flags/${item.itemKey}/acknowledge`, {}),
                  'Flag acknowledged.',
                )
              }
            >
              Acknowledge flag
            </button>
          ) : item.supportState === 'FLAGGED' && item.acknowledged ? (
            <span className="small">Acknowledged</span>
          ) : null
        }
      />
      <VersionComparison versionId={version.id} versions={detail.data.versions ?? []} />
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      {open && (
        <div className="actions">
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              void act(
                () => api.post(`/api/v1/staff/brd-versions/${version.id}/approve`, { expectedContentHash: version.contentHash }),
                'Approved. The BRD will be released to the client.',
              )
            }
          >
            Approve this version
          </button>
          <label>
            Correction notes (one per line)
            <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} maxLength={5000} />
          </label>
          <button
            type="button"
            disabled={busy || !notes.trim()}
            onClick={() =>
              void act(
                () =>
                  api.post(`/api/v1/staff/brd-versions/${version.id}/request-revision`, {
                    notes: notes
                      .split('\n')
                      .map((line) => line.trim())
                      .filter(Boolean)
                      .map((instruction) => ({ itemKey: null, instruction })),
                  }),
                'Revision requested. The analysis will run again.',
              )
            }
          >
            Request revision
          </button>
          <label>
            Reason for rejection (at least 10 characters)
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={2000} />
          </label>
          <button
            type="button"
            disabled={busy || reason.trim().length < 10}
            onClick={() => void act(() => api.post(`/api/v1/staff/brd-versions/${version.id}/reject`, { reason }), 'Rejected.')}
          >
            Reject
          </button>
        </div>
      )}
    </section>
  );
}
