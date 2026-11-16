import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { ClientBrd, ClientReview, RequirementState } from '../api/types';
import { money } from '../ui/money';
import { Badge, Callout, formatDateTime, type Tone } from '../ui/components';
import { BrdItems } from './BrdItems';

const WAITING: Partial<Record<RequirementState, string>> = {
  ANALYSING: 'The BA agent is analysing the code and documents. The BRD will appear here for your review.',
  AWAITING_CLARIFICATION: 'The BA agent has a question for you (see Clarifications). Analysis continues after your answer.',
  BRD_DRAFTED: 'The BRD is being prepared for your review.',
  REVISION_REQUESTED: 'Your change request is being analysed; a new BRD version will follow.',
  CLIENT_CHANGES_REQUESTED: 'Your change request is being analysed; a new BRD version will follow.',
};

const VERDICT: Record<string, { label: string; tone: Tone }> = {
  FEASIBLE: { label: 'Feasible', tone: 'success' },
  FEASIBLE_WITH_RISKS: { label: 'Feasible with risks', tone: 'warning' },
  NOT_FEASIBLE: { label: 'Not feasible', tone: 'danger' },
};

const STAGE: Record<ClientReview['stage'], { label: string; tone: Tone }> = {
  AUTHOR_REVIEW: { label: 'Author review', tone: 'warning' },
  AWAITING_APPROVAL: { label: 'Waiting for BRD Approver', tone: 'info' },
  PUBLISHING: { label: 'Approved, publishing', tone: 'info' },
  APPROVED: { label: 'Approved', tone: 'success' },
  CLOSED_NOT_FEASIBLE: { label: 'Closed: not feasible', tone: 'danger' },
};

/**
 * GD-24: the BRD goes straight to the client. The author accepts flagged statements and confirms (or requests
 * changes); a BRD Approver other than the author approves; the BRD is then released automatically.
 */
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
    refetchInterval: (q) => (q.state.data?.review?.stage === 'PUBLISHING' ? 4000 : false),
  });
  const [text, setText] = useState('');
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [changesOpen, setChangesOpen] = useState(false);

  async function act(path: string, body: unknown, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      await api.post(`/api/v1/requirements/${requirementId}/brd/${path}`, body);
      setMessage({ tone: 'info', text: done });
      setText('');
      setChangesOpen(false);
      await queryClient.invalidateQueries();
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'The action failed.' });
    } finally {
      setBusy(false);
    }
  }

  async function requestChanges(event: FormEvent, review: ClientReview) {
    event.preventDefault();
    const charged = review.revisionsUsed >= review.revisionsIncluded && review.nextRevisionPrice != null;
    if (charged && !window.confirm(`The ${review.revisionsIncluded} included change requests are used. This one is charged `
        + `${money(review.nextRevisionCurrency ?? 'INR', review.nextRevisionPrice ?? 0)}. Continue?`)) {
      return;
    }
    await act('request-changes', { text: text.trim(), acceptCharge: charged },
      'Change request sent. The BA agent will produce a new BRD version.');
  }

  const data = brd.data;
  const review = data?.review;
  const version = data?.version;
  return (
    <section aria-label="Business requirements document">
      <div className="spread">
        <h2>Business requirements document</h2>
        {review && <Badge tone={STAGE[review.stage].tone}>{STAGE[review.stage].label}</Badge>}
      </div>
      {brd.isPending && <p>Loading…</p>}
      {!brd.isPending && !version?.items && (
        <p>{WAITING[requirementState] ?? 'No BRD yet. It appears here once the analysis has run.'}</p>
      )}
      {version?.items && (
        <>
          <p className="small">
            Version {version.versionNo} · produced {formatDateTime(version.createdAt)}
            {review && review.revisionsUsed > 0 && ` · change requests ${review.revisionsUsed} of ${review.revisionsIncluded} included used`}
          </p>
          {review?.feasibility && (
            <Callout
              tone={review.feasibility === 'NOT_FEASIBLE' ? 'danger' : review.feasibility === 'FEASIBLE' ? 'success' : 'warning'}
              title={`Feasibility: ${VERDICT[review.feasibility]?.label ?? review.feasibility}`}
            >
              <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
                {review.feasibilityReasons.map((r) => (
                  <li key={r}>{r}</li>
                ))}
              </ul>
              {review.stage === 'CLOSED_NOT_FEASIBLE' && (
                <p className="small" style={{ marginTop: '0.5rem' }}>
                  The requirement is closed and its BRD charge is waived.
                </p>
              )}
            </Callout>
          )}
          {version.understanding && <p>{version.understanding}</p>}
          <BrdItems
            items={version.items}
            renderActions={(item) => {
              if (!review || item.supportState !== 'FLAGGED') return null;
              if (review.acceptedFlags.includes(item.itemKey)) {
                return <span className="small">Accepted as is by the author.</span>;
              }
              return review.canAcceptFlags ? (
                <button type="button" className="btn-secondary btn-sm" disabled={busy}
                  onClick={() => void act(`flags/${encodeURIComponent(item.itemKey)}/accept`, {}, 'Statement accepted as is.')}>
                  Accept as is
                </button>
              ) : null;
            }}
          />
          {review && (
            <div className="stack" style={{ marginTop: '1rem' }}>
              {review.authorConfirmedBy && (
                <p className="small">
                  Confirmed by {review.authorConfirmedBy} · {formatDateTime(review.authorConfirmedAt)}
                  {review.approvedBy ? ` · approved by ${review.approvedBy}` : ''}
                </p>
              )}
              {review.blockedReason && <Callout tone="info">{review.blockedReason}</Callout>}
              {review.stage === 'AUTHOR_REVIEW' && !review.isAuthor && (
                <p className="small">The requirement&apos;s author reviews this BRD first; you approve it afterwards.</p>
              )}
              {review.stage === 'AWAITING_APPROVAL' && review.isAuthor && !review.canApprove && (
                <p className="small">You confirmed this BRD. A BRD Approver of the project gives the final approval.</p>
              )}
              <div className="row">
                {review.canConfirm && (
                  <button type="button" className="btn-primary" disabled={busy}
                    onClick={() => void act('confirm', {}, 'BRD confirmed. The project\'s BRD Approvers have been asked to approve it.')}>
                    Confirm BRD
                  </button>
                )}
                {review.canApprove && (
                  <button type="button" className="btn-primary" disabled={busy}
                    onClick={() => {
                      if (window.confirm('Approve this BRD as final? It is published to the project once the automated checks pass.')) {
                        void act('approve', { expectedContentHash: version.contentHash }, 'BRD approved. It is published once the automated checks pass.');
                      }
                    }}>
                    Approve BRD
                  </button>
                )}
                {review.canRequestChanges && !changesOpen && (
                  <button type="button" className="btn-secondary" disabled={busy} onClick={() => setChangesOpen(true)}>
                    Request changes
                  </button>
                )}
              </div>
              {review.stage === 'APPROVED' && review.isAuthor && (
                <form onSubmit={(e) => {
                  e.preventDefault();
                  setBusy(true);
                  api.post(`/api/v1/requirements/${requirementId}/client-changes`, { text: text.trim() })
                    .then(async () => {
                      setText('');
                      setMessage({ tone: 'info', text: 'Your change request was submitted. A BRD Approver assesses it; nothing changes until it is accepted.' });
                      await queryClient.invalidateQueries();
                    })
                    .catch(() => setMessage({ tone: 'danger', text: 'The change request could not be sent.' }))
                    .finally(() => setBusy(false));
                }} className="stack">
                  <label>
                    Request a change to the approved scope (10 to 5000 characters)
                    <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={3} />
                  </label>
                  <div className="row">
                    <button type="submit" disabled={busy || text.trim().length < 10}>Submit change request</button>
                  </div>
                </form>
              )}
              {review.canRequestChanges && changesOpen && (
                <form onSubmit={(e) => void requestChanges(e, review)} className="stack">
                  <label>
                    What should change? (10 to 5000 characters)
                    <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={4} />
                  </label>
                  <p className="small">
                    {review.revisionsUsed < review.revisionsIncluded
                      ? `${review.revisionsIncluded - review.revisionsUsed} of ${review.revisionsIncluded} included change requests left.`
                      : review.nextRevisionPrice != null
                        ? `Included change requests are used; this one is charged ${money(review.nextRevisionCurrency ?? 'INR', review.nextRevisionPrice)}.`
                        : 'Included change requests are used.'}
                  </p>
                  <div className="row">
                    <button type="submit" disabled={busy || text.trim().length < 10}>Send change request</button>
                    <button type="button" className="btn-ghost" onClick={() => setChangesOpen(false)}>Cancel</button>
                  </div>
                </form>
              )}
            </div>
          )}
        </>
      )}
      {message && (
        <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">
          {message.text}
        </p>
      )}
    </section>
  );
}
