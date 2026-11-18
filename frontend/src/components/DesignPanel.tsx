import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Badge, Callout, Modal, formatDateTime, type Tone } from '../ui/components';

// GD-26: the Technical design stage of a requirement.

interface DesignItem {
  itemKey: string;
  ordinal: number;
  section: string;
  kind: string;
  title: string;
  detail: string;
  component: string | null;
  brdRefs: string[];
  supportState: 'VERIFIED' | 'FLAGGED' | 'NOT_TRACED';
  evidence: { path: string; startLine: number; endLine: number; quote: string }[];
  accepted: boolean;
}

interface Coverage {
  itemKey: string;
  section: string;
  statement: string;
  covered: boolean;
  coveredBy: string[];
}

interface DesignVersion {
  id: string;
  versionNo: number;
  status: string;
  brdVersionNo: number;
  summary: string | null;
  openQuestions: string[];
  contentHash: string | null;
  modelId: string | null;
  changeRequest: string | null;
  failureCode: string | null;
  attempts: number;
  createdAt: string;
  items: DesignItem[];
  coverage: Coverage[];
}

type Stage = 'BRD_NOT_APPROVED' | 'NOT_STARTED' | 'GENERATING' | 'FAILED' | 'IN_REVIEW' | 'APPROVED';

interface DesignReview {
  stage: Stage;
  isAuthor: boolean;
  isTechnicalApprover: boolean;
  canStart: boolean;
  canRetry: boolean;
  canAcceptFlags: boolean;
  canApprove: boolean;
  canRequestChanges: boolean;
  unacceptedFlags: string[];
  approvedBy: string | null;
  approvedAt: string | null;
  approvalNote: string | null;
  brdOutdated: boolean;
  currentBrdVersionNo: number | null;
  technicalApprovers: number;
  blockedReason: string | null;
}

interface DesignHistory {
  versionNo: number;
  status: string;
  label: string;
  createdAt: string;
  changeRequest: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  downloadable: boolean;
}

interface DesignState {
  requirementId: string;
  review: DesignReview;
  version: DesignVersion | null;
  history: DesignHistory[];
}

const STAGE: Record<Stage, { label: string; tone: Tone }> = {
  BRD_NOT_APPROVED: { label: 'Waiting for the BRD', tone: 'neutral' },
  NOT_STARTED: { label: 'Waiting for technical design', tone: 'warning' },
  GENERATING: { label: 'Being written', tone: 'info' },
  FAILED: { label: 'Failed', tone: 'danger' },
  IN_REVIEW: { label: 'In review', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
};

const SECTIONS: Record<string, string> = {
  ARCHITECTURE: 'Architecture',
  COMPONENTS: 'Components',
  API_CHANGES: 'API changes',
  DATA_MODEL: 'Data model',
  INTEGRATIONS: 'Integrations',
  SECURITY: 'Security',
  NON_FUNCTIONAL: 'Non-functional design',
  MIGRATION_AND_ROLLOUT: 'Migration and rollout',
  TEST_PLAN: 'Test plan',
  RISKS: 'Risks',
};

const BRD_SECTIONS: Record<string, string> = {
  SCENARIOS: 'Scenario',
  ACCEPTANCE_CRITERIA: 'Acceptance criterion',
  IMPACTED_COMPONENTS: 'Impact',
  NON_FUNCTIONAL: 'Non-functional',
};

const SUPPORT: Record<DesignItem['supportState'], string> = {
  VERIFIED: 'Based on verified code',
  FLAGGED: 'Code reference could not be verified',
  NOT_TRACED: 'Not traced to code',
};

function exportUrl(requirementId: string, format: 'pdf' | 'docx', versionNo?: number): string {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const version = versionNo == null ? '' : `&version=${versionNo}`;
  return `/api/v1/requirements/${requirementId}/design/export?format=${format}&tz=${encodeURIComponent(tz)}${version}`;
}

function failureText(code: string | null): string {
  if (code === 'COST_CAP') return 'The AI spending limit was reached.';
  if (code?.startsWith('MODEL_')) return 'The AI model did not return a usable design.';
  return 'The design could not be produced.';
}

/**
 * Technical design (GD-26, GD-27): after BRD approval it is handed to the project's Technical Approvers, who start it,
 * review it and approve it (one may approve alone, after confirming so). The author follows it and may request
 * changes. Shows coverage of the BRD so gaps are visible.
 */
export function DesignPanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const design = useQuery({
    queryKey: ['design', requirementId],
    queryFn: () => api.get<DesignState>(`/api/v1/requirements/${requirementId}/design`),
    refetchInterval: (q) => (q.state.data?.review.stage === 'GENERATING' ? 4000 : false),
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);
  const [changesOpen, setChangesOpen] = useState(false);
  const [approving, setApproving] = useState(false);
  const [aloneConfirmed, setAloneConfirmed] = useState(false);
  const [text, setText] = useState('');

  async function act(path: string, body: unknown, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      const next = await api.post<DesignState>(`/api/v1/requirements/${requirementId}/design/${path}`, body);
      queryClient.setQueryData(['design', requirementId], next);
      setMessage({ tone: 'info', text: done });
      setChangesOpen(false);
      setText('');
      await queryClient.invalidateQueries({ queryKey: ['notifications'] });
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'The action failed.' });
    } finally {
      setBusy(false);
    }
  }

  function requestChanges(event: FormEvent) {
    event.preventDefault();
    void act('request-changes', { text: text.trim() }, 'Change request sent. The Architect agent is writing a new version.');
  }

  const data = design.data;
  if (design.isPending) return <section aria-label="Technical design"><h2>Technical design</h2><p>Loading…</p></section>;
  if (!data) return null;
  const { review, version } = data;
  const stage = STAGE[review.stage];
  const uncovered = version?.coverage.filter((c) => !c.covered) ?? [];
  const showContent = version && version.status !== 'GENERATING' && version.status !== 'FAILED';

  return (
    <section aria-label="Technical design">
      <div className="spread">
        <h2>Technical design</h2>
        <div className="row">
          {review.stage === 'APPROVED' && (
            <>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'pdf')} download>Download PDF</a>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'docx')} download>Download Word</a>
            </>
          )}
          <Badge tone={stage.tone}>{stage.label}</Badge>
        </div>
      </div>

      {review.blockedReason && review.technicalApprovers === 0 && (
        <Callout tone="warning" title="No Technical Approver">{review.blockedReason}</Callout>
      )}
      {review.stage === 'NOT_STARTED' && review.technicalApprovers > 0 && (
        <p>
          The BRD is approved and handed to the project&apos;s Technical Approvers. The Architect agent writes the
          technical design: components, API and data changes, security, rollout and a test plan, checked against the code
          and the approved BRD.
          {!review.canStart && ' A Technical Approver starts it; you can follow its progress here.'}
        </p>
      )}
      {review.stage === 'GENERATING' && (
        <p className="row" style={{ gap: '0.6rem' }}>
          <span className="spinner" aria-hidden="true" />
          The Architect agent is writing the design from the approved BRD and the code. This usually takes one or two
          minutes; you are notified when it is ready.
        </p>
      )}
      {review.stage === 'FAILED' && version && (
        <Callout tone="danger" title="The design could not be written">
          {failureText(version.failureCode)}{' '}
          {review.canRetry
            ? `You can try again (${version.attempts} of 3 attempts used).`
            : 'All three attempts are used. Contact Garuda support.'}
        </Callout>
      )}
      {review.brdOutdated && version && (
        <Callout tone="warning" title="Based on an older BRD">
          This design was written for BRD version {version.brdVersionNo}; the approved BRD is now version{' '}
          {review.currentBrdVersionNo}. Start a new design to include the changes.
        </Callout>
      )}

      {(review.canStart || review.canRetry) && (
        <div className="row" style={{ margin: '0.75rem 0' }}>
          {review.canStart && (
            <button type="button" className="btn-primary" disabled={busy}
              onClick={() => void act('start', {}, 'The Architect agent has started. You are notified when the design is ready.')}>
              {version ? 'Start a new design' : 'Start technical design'}
            </button>
          )}
          {review.canRetry && (
            <button type="button" className="btn-primary" disabled={busy}
              onClick={() => void act('retry', {}, 'Trying again. You are notified when the design is ready.')}>
              Try again
            </button>
          )}
        </div>
      )}

      {showContent && version && (
        <>
          <p className="small">
            Version {version.versionNo} · based on approved BRD version {version.brdVersionNo} · produced{' '}
            {formatDateTime(version.createdAt)}
            {version.changeRequest && ` · requested change: “${version.changeRequest}”`}
          </p>
          {version.summary && <p>{version.summary}</p>}

          {version.coverage.length > 0 && (
            <Callout
              tone={uncovered.length === 0 ? 'success' : 'warning'}
              title={`BRD coverage: ${version.coverage.length - uncovered.length} of ${version.coverage.length} statements addressed`}
            >
              {uncovered.length === 0
                ? 'Every scenario, acceptance criterion, impact and non-functional point of the BRD is addressed by the design.'
                : (
                  <>
                    Not addressed by any design item:
                    <ul style={{ margin: '0.35rem 0 0', paddingLeft: '1.1rem' }}>
                      {uncovered.map((c) => (
                        <li key={c.itemKey}>
                          <span className="small">{BRD_SECTIONS[c.section] ?? c.section}:</span> {c.statement}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
            </Callout>
          )}

          {Object.keys(SECTIONS).filter((s) => version.items.some((i) => i.section === s)).map((section) => (
            <section key={section} aria-label={SECTIONS[section]}>
              <h3>{SECTIONS[section]}</h3>
              <ul className="brd-items">
                {version.items.filter((i) => i.section === section).map((item) => (
                  <li key={item.itemKey} className={`support-${item.supportState.toLowerCase()}`}>
                    <span className="label">{item.kind}</span> <strong>{item.title}</strong>
                    <div style={{ whiteSpace: 'pre-wrap', margin: '0.2rem 0' }}>{item.detail}</div>
                    <div className="small">
                      {SUPPORT[item.supportState]}
                      {item.component && ` · Component: ${item.component}`}
                      {item.brdRefs.length > 0 && ` · Addresses ${item.brdRefs.length} BRD statement${item.brdRefs.length > 1 ? 's' : ''}`}
                    </div>
                    {item.evidence.length > 0 && (
                      <ul className="evidence">
                        {item.evidence.map((e, index) => (
                          <li key={index}><code>{e.path} lines {e.startLine}–{e.endLine}</code></li>
                        ))}
                      </ul>
                    )}
                    {item.supportState === 'FLAGGED' && (
                      item.accepted
                        ? <span className="small">Accepted as is by the author.</span>
                        : review.canAcceptFlags && (
                          <button type="button" className="btn-secondary btn-sm" disabled={busy}
                            onClick={() => void act(`flags/${encodeURIComponent(item.itemKey)}/accept`, {}, 'Item accepted as is.')}>
                            Accept as is
                          </button>
                        )
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}

          {version.openQuestions.length > 0 && (
            <section aria-label="Open questions">
              <h3>Open questions</h3>
              <ul className="brd-items">
                {version.openQuestions.map((q) => <li key={q}>{q}</li>)}
              </ul>
            </section>
          )}

          <div className="stack" style={{ marginTop: '1rem' }}>
            {review.approvedBy && (
              <p className="small">
                Approved by {review.approvedBy} · {formatDateTime(review.approvedAt)}
                {review.approvalNote && ` · ${review.approvalNote}`}
              </p>
            )}
            {review.blockedReason && review.technicalApprovers > 0 && <Callout tone="info">{review.blockedReason}</Callout>}
            {review.stage === 'IN_REVIEW' && !review.isTechnicalApprover && (
              <p className="small">
                A Technical Approver reviews and approves this design.
                {review.isAuthor && ' You can request changes if it does not match what you need.'}
              </p>
            )}
            <div className="row">
              {review.canApprove && (
                <button type="button" className="btn-primary" disabled={busy}
                  onClick={() => { setAloneConfirmed(false); setApproving(true); }}>
                  Approve design
                </button>
              )}
              {review.canRequestChanges && !changesOpen && (
                <button type="button" className="btn-secondary" disabled={busy} onClick={() => setChangesOpen(true)}>
                  Request changes
                </button>
              )}
            </div>
            {review.canRequestChanges && changesOpen && (
              <form onSubmit={requestChanges} className="stack">
                <label>
                  What should change? (10 to 5000 characters)
                  <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={4} />
                </label>
                <div className="row">
                  <button type="submit" disabled={busy || text.trim().length < 10}>Send change request</button>
                  <button type="button" className="btn-ghost" onClick={() => setChangesOpen(false)}>Cancel</button>
                </div>
              </form>
            )}
          </div>
        </>
      )}

      {data.history.length > 1 && (
        <details style={{ marginTop: '1.25rem' }}>
          <summary><strong>Version history</strong> <span className="small">({data.history.length} versions)</span></summary>
          <div className="table-wrap" style={{ marginTop: '0.75rem' }}>
            <table>
              <thead>
                <tr><th>Version</th><th>Status</th><th>Produced</th><th>Requested change</th><th>Approved by</th><th aria-label="Downloads" /></tr>
              </thead>
              <tbody>
                {data.history.map((h) => (
                  <tr key={h.versionNo}>
                    <td>v{h.versionNo}</td>
                    <td><Badge tone={h.status === 'APPROVED' ? 'success' : h.downloadable ? 'info' : 'neutral'}>{h.label}</Badge></td>
                    <td>{formatDateTime(h.createdAt)}</td>
                    <td>{h.changeRequest ?? '—'}</td>
                    <td>{h.approvedBy ? `${h.approvedBy} · ${formatDateTime(h.approvedAt)}` : '—'}</td>
                    <td>
                      {h.downloadable && (
                        <span className="row" style={{ flexWrap: 'nowrap' }}>
                          <a className="btn btn-ghost btn-sm" href={exportUrl(requirementId, 'pdf', h.versionNo)} download>PDF</a>
                          <a className="btn btn-ghost btn-sm" href={exportUrl(requirementId, 'docx', h.versionNo)} download>Word</a>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}

      {approving && version && (
        <Modal
          title="Approve the technical design"
          description={`Version ${version.versionNo}, based on approved BRD version ${version.brdVersionNo}.`}
          onClose={() => setApproving(false)}
          footer={
            <>
              <button type="button" className="btn-secondary" onClick={() => setApproving(false)}>Cancel</button>
              <button type="button" className="btn-primary" disabled={!aloneConfirmed || busy}
                onClick={() => {
                  setApproving(false);
                  void act('approve', { expectedContentHash: version.contentHash, confirmApproveAlone: true },
                    'Design approved.');
                }}>
                Approve design
              </button>
            </>
          }
        >
          <p>
            You are approving this design as the final technical design of the requirement. No second reviewer checks it,
            and the approval is recorded in the audit trail with this confirmation.
          </p>
          {uncovered.length > 0 && (
            <Callout tone="warning">{uncovered.length} BRD statement(s) are not addressed by the design.</Callout>
          )}
          <label className="check">
            <input type="checkbox" checked={aloneConfirmed} onChange={(e) => setAloneConfirmed(e.target.checked)} />
            <span>I have reviewed this design and approve it on my own, without a second reviewer.</span>
          </label>
        </Modal>
      )}
      {message && (
        <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>
      )}
    </section>
  );
}
