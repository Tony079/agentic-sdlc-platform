import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Badge, Callout, Modal, formatDateTime, type Tone } from '../ui/components';
import { ActivityTimeline } from './ActivityTimeline';
import { FoldSection } from '../ui/Fold';

// GD-33: the Estimation stage of a requirement.

const ROLES = ['FRONTEND', 'BACKEND', 'DATA', 'DEVOPS', 'QA', 'DESIGN', 'OTHER'] as const;
type Role = (typeof ROLES)[number];
const ROLE_LABEL: Record<string, string> = {
  FRONTEND: 'Frontend', BACKEND: 'Backend', DATA: 'Data', DEVOPS: 'DevOps', QA: 'QA', DESIGN: 'Design', OTHER: 'Other',
};
const PHASES = ['SETUP', 'BUILD', 'TEST', 'HARDENING', 'RELEASE'] as const;
const PHASE_LABEL: Record<string, string> = {
  SETUP: 'Setup', BUILD: 'Build', TEST: 'Test', HARDENING: 'Hardening', RELEASE: 'Release',
};
const FLAG_LABEL: Record<string, string> = {
  CLIPPED: 'Figures were out of range and were corrected',
  UNCERTAIN: 'Wide range: the high figure is more than three times the low one',
  LARGE: 'Large item: consider splitting it',
};

interface Settings {
  team: Partial<Record<Role, number>>;
  daysPerWeek: number;
  contingencyPercent: number;
  currency: string | null;
  dayRates: Partial<Record<Role, number>> | null;
}

interface Adjustment { low: number; likely: number; high: number; reason: string; by: string; at: string }

interface EstimateItem {
  itemKey: string;
  ordinal: number;
  title: string;
  detail: string;
  role: string;
  phase: string;
  designRefs: string[];
  proposedLow: number;
  proposedLikely: number;
  proposedHigh: number;
  low: number;
  likely: number;
  high: number;
  expected: number;
  assumptions: string[];
  risk: string | null;
  dependsOn: string[];
  flags: string[];
  accepted: boolean;
  adjustments: Adjustment[];
}

interface Coverage { designKey: string; section: string; title: string; covered: boolean; accepted: boolean; coveredBy: string[] }
interface Range { p50: number; p85: number }
interface RoleLine {
  role: string; effortP50: number; effortP85: number; people: number; assumedPerson: boolean; weeksP50: number;
  weeksP85: number; costP50: number | null; costP85: number | null;
}
interface Summary {
  effort: Range; effortWithContingency: Range; byRole: RoleLine[]; weeks: Range; criticalPathWeeks: number;
  costP50: number | null; costP85: number | null; currency: string | null; contingencyPercent: number; notes: string[];
}

interface EstimateVersion {
  id: string; versionNo: number; status: string; designVersionNo: number; reviewHash: string | null;
  changeRequest: string | null; failureCode: string | null; attempts: number; createdAt: string; settings: Settings;
  assumptions: string[]; excluded: string[]; items: EstimateItem[]; coverage: Coverage[]; summary: Summary | null;
}

type Stage = 'DESIGN_NOT_APPROVED' | 'NOT_STARTED' | 'GENERATING' | 'FAILED' | 'IN_REVIEW' | 'APPROVED';

interface EstimateReview {
  stage: Stage; isEstimator: boolean; isApprover: boolean; canStart: boolean; canRetry: boolean; canAdjust: boolean;
  canApprove: boolean; canRequestChanges: boolean; unacceptedFlags: string[]; uncoveredDesignItems: string[];
  approvedBy: string | null; approvedAt: string | null; approvalNote: string | null; designOutdated: boolean;
  currentDesignVersionNo: number | null; blockedReason: string | null;
}

interface EstimateHistory {
  versionNo: number; status: string; createdAt: string; changeRequest: string | null; approvedBy: string | null;
  approvedAt: string | null;
}

interface EstimateState {
  requirementId: string; canSee: boolean; review: EstimateReview; version: EstimateVersion | null;
  history: EstimateHistory[]; defaults: Settings | null;
}

const STAGE: Record<Stage, { label: string; tone: Tone }> = {
  DESIGN_NOT_APPROVED: { label: 'Waiting for the design', tone: 'neutral' },
  NOT_STARTED: { label: 'Waiting for estimation', tone: 'warning' },
  GENERATING: { label: 'Being written', tone: 'info' },
  FAILED: { label: 'Failed', tone: 'danger' },
  IN_REVIEW: { label: 'In review', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
};

function exportUrl(requirementId: string, format: 'pdf' | 'docx' | 'csv', versionNo?: number): string {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const version = versionNo == null ? '' : `&version=${versionNo}`;
  return `/api/v1/requirements/${requirementId}/estimate/export?format=${format}&tz=${encodeURIComponent(tz)}${version}`;
}

const one = (n: number) => (Math.round(n * 10) / 10).toLocaleString();
const range = (r: Range) => `${one(r.p50)} – ${one(r.p85)}`;
const money = (currency: string, n: number) => `${currency} ${Math.round(n).toLocaleString()}`;

function SettingsForm({ initial, busy, onStart }: { initial: Settings; busy: boolean; onStart: (s: Settings) => void }) {
  const [s, setS] = useState<Settings>(initial);
  const [priced, setPriced] = useState(!!initial.dayRates && Object.keys(initial.dayRates).length > 0);
  const setTeam = (role: Role, n: number) => setS({ ...s, team: { ...s.team, [role]: n } });
  const setRate = (role: Role, n: number) => setS({ ...s, dayRates: { ...(s.dayRates ?? {}), [role]: n } });
  return (
    <form className="stack" onSubmit={(e) => {
      e.preventDefault();
      onStart(priced ? s : { ...s, currency: null, dayRates: null });
    }}>
      <p className="small">
        The Estimation agent proposes the work items and their effort; Garuda computes the totals, the timeline for this team
        and, with day rates, the cost. These settings are remembered for the project.
      </p>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Role</th><th>People</th>{priced && <th>Day rate</th>}</tr></thead>
          <tbody>
            {ROLES.map((role) => (
              <tr key={role}>
                <td>{ROLE_LABEL[role]}</td>
                <td>
                  <input type="number" min={0} max={50} aria-label={`${ROLE_LABEL[role]} people`} value={s.team[role] ?? 0}
                    onChange={(e) => setTeam(role, Number(e.target.value))} style={{ width: '5rem' }} />
                </td>
                {priced && (
                  <td>
                    <input type="number" min={0} aria-label={`${ROLE_LABEL[role]} day rate`} value={s.dayRates?.[role] ?? 0}
                      onChange={(e) => setRate(role, Number(e.target.value))} style={{ width: '8rem' }} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
        <label>
          Working days per week
          <input type="number" min={1} max={7} value={s.daysPerWeek}
            onChange={(e) => setS({ ...s, daysPerWeek: Number(e.target.value) })} style={{ width: '5rem' }} />
        </label>
        <label>
          Contingency %
          <input type="number" min={0} max={100} value={s.contingencyPercent}
            onChange={(e) => setS({ ...s, contingencyPercent: Number(e.target.value) })} style={{ width: '5rem' }} />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={priced} onChange={(e) => setPriced(e.target.checked)} />
        <span>Include cost (day rate per role)</span>
      </label>
      {priced && (
        <label>
          Currency (ISO code, for example INR or USD)
          <input value={s.currency ?? ''} maxLength={3} onChange={(e) => setS({ ...s, currency: e.target.value.toUpperCase() })}
            style={{ width: '6rem' }} />
        </label>
      )}
      <div className="row">
        <button type="submit" className="btn-primary" disabled={busy}>Start estimate</button>
      </div>
    </form>
  );
}

function AdjustForm({ item, busy, onSave, onCancel }: {
  item: EstimateItem; busy: boolean; onSave: (low: number, likely: number, high: number, reason: string) => void;
  onCancel: () => void;
}) {
  const [low, setLow] = useState(item.low);
  const [likely, setLikely] = useState(item.likely);
  const [high, setHigh] = useState(item.high);
  const [reason, setReason] = useState('');
  const ordered = low > 0 && likely >= low && high >= likely;
  return (
    <form className="stack" style={{ marginTop: '0.5rem' }} onSubmit={(e: FormEvent) => {
      e.preventDefault();
      onSave(low, likely, high, reason.trim());
    }}>
      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        <label>Low days<input type="number" step="0.25" min={0.25} value={low} onChange={(e) => setLow(Number(e.target.value))} style={{ width: '6rem' }} /></label>
        <label>Likely days<input type="number" step="0.25" min={0.25} value={likely} onChange={(e) => setLikely(Number(e.target.value))} style={{ width: '6rem' }} /></label>
        <label>High days<input type="number" step="0.25" min={0.25} value={high} onChange={(e) => setHigh(Number(e.target.value))} style={{ width: '6rem' }} /></label>
      </div>
      <label>
        Reason (10 to 1000 characters; shown with the estimate)
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} rows={2} />
      </label>
      {!ordered && <p className="error">Low must be at most likely, and likely at most high.</p>}
      <div className="row">
        <button type="submit" className="btn-primary btn-sm" disabled={busy || !ordered || reason.trim().length < 10}>Save adjustment</button>
        <button type="button" className="btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/**
 * Estimation (GD-33): after design approval, the project's Estimators start the estimate, review and adjust it; a BRD
 * Approver approves it. Only Estimators and BRD Approvers see its content; others see its status.
 */
export function EstimatePanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const estimate = useQuery({
    queryKey: ['estimate', requirementId],
    queryFn: () => api.get<EstimateState>(`/api/v1/requirements/${requirementId}/estimate`),
    refetchInterval: (q) => (q.state.data?.review.stage === 'GENERATING' ? 4000 : false),
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);
  const [adjusting, setAdjusting] = useState<string | null>(null);
  const [changesOpen, setChangesOpen] = useState(false);
  const [text, setText] = useState('');
  const [approving, setApproving] = useState(false);
  const [aloneConfirmed, setAloneConfirmed] = useState(false);

  async function act(path: string, body: unknown, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      const next = await api.post<EstimateState>(`/api/v1/requirements/${requirementId}/estimate/${path}`, body);
      queryClient.setQueryData(['estimate', requirementId], next);
      setMessage({ tone: 'info', text: done });
      setAdjusting(null);
      setChangesOpen(false);
      setText('');
      await queryClient.invalidateQueries({ queryKey: ['notifications'] });
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'The action failed.' });
    } finally {
      setBusy(false);
    }
  }

  const data = estimate.data;
  if (estimate.isPending) return <section aria-label="Estimate"><h2>Estimate</h2><p>Loading…</p></section>;
  if (!data) return null;
  const { review, version } = data;
  const stage = STAGE[review.stage];
  const showContent = data.canSee && version && version.status !== 'GENERATING' && version.status !== 'FAILED';
  const s = version?.summary ?? null;
  const gaps = version?.coverage.filter((c) => !c.covered) ?? [];

  return (
    <FoldSection
      label="Estimate"
      title="Estimate"
      foldKey={`fold:${requirementId}:estimate`}
      summary={showContent && s ? `Version ${version.versionNo} · ${range(s.effortWithContingency)} person-days` : undefined}
      actions={
        <>
          {data.canSee && review.stage === 'APPROVED' && (
            <>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'pdf')} download>PDF</a>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'docx')} download>Word</a>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'csv')} download>Spreadsheet</a>
            </>
          )}
          <Badge tone={stage.tone}>{stage.label}</Badge>
        </>
      }
    >
      {!data.canSee && (
        <p className="small">
          {review.stage === 'DESIGN_NOT_APPROVED'
            ? 'The estimate starts after the technical design is approved.'
            : 'Only the project’s Estimators and BRD Approvers can see the estimate.'}
        </p>
      )}
      {data.canSee && review.stage === 'DESIGN_NOT_APPROVED' && (
        <p className="small">The estimate starts after the technical design is approved.</p>
      )}
      {review.blockedReason && review.stage === 'NOT_STARTED' && <Callout tone="info">{review.blockedReason}</Callout>}
      {review.canStart && (
        <SettingsForm initial={version?.settings ?? data.defaults ?? { team: {}, daysPerWeek: 5, contingencyPercent: 15, currency: null, dayRates: null }}
          busy={busy} onStart={(settings) => void act('start', { settings },
            'The Estimation agent has started. You are notified when the estimate is ready.')} />
      )}
      {data.canSee && review.stage === 'GENERATING' && (
        <ActivityTimeline requirementId={requirementId} stage="ESTIMATE" live title="What the Estimation agent is doing" />
      )}
      {data.canSee && review.stage === 'FAILED' && version && (
        <>
          <Callout tone="danger" title="The estimate could not be written">
            {review.canRetry ? `You can try again (${version.attempts} of 3 attempts used).` : 'All three attempts are used. Contact Garuda support.'}
          </Callout>
          {review.canRetry && (
            <button type="button" className="btn-primary" disabled={busy}
              onClick={() => void act('retry', {}, 'Trying again. You are notified when the estimate is ready.')}>Try again</button>
          )}
          <ActivityTimeline requirementId={requirementId} stage="ESTIMATE" live={false} expanded title="What happened" />
        </>
      )}
      {review.designOutdated && version && (
        <Callout tone="warning" title="Based on an older design">
          This estimate was made for design version {version.designVersionNo}; the approved design is now version{' '}
          {review.currentDesignVersionNo}. Start a new estimate to include the changes.
        </Callout>
      )}

      {showContent && version && (
        <>
          <p className="small">
            Version {version.versionNo} · based on approved design version {version.designVersionNo} · produced{' '}
            {formatDateTime(version.createdAt)}
            {version.changeRequest && ` · requested change: “${version.changeRequest}”`}
          </p>
          {s && (
            <div className="stack">
              <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
                <div><div className="label">Effort (person-days)</div><strong>{range(s.effortWithContingency)}</strong>
                  <div className="small">incl. {s.contingencyPercent}% contingency</div></div>
                <div><div className="label">Timeline (weeks)</div><strong>{range(s.weeks)}</strong>
                  <div className="small">for the team in the settings</div></div>
                {s.costP50 != null && s.costP85 != null && s.currency && (
                  <div><div className="label">Cost</div><strong>{money(s.currency, s.costP50)} – {money(s.currency, s.costP85)}</strong>
                    <div className="small">from the day rates</div></div>
                )}
              </div>
              <p className="small">
                Ranges are P50 to P85: a 50% and an 85% chance of finishing within them. Garuda computes them from each
                item’s low, likely and high figures; the model only proposes the items.
              </p>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Role</th><th>People</th><th>Person-days</th><th>Weeks</th>{s.costP50 != null && <th>Cost</th>}</tr></thead>
                  <tbody>
                    {s.byRole.map((r) => (
                      <tr key={r.role}>
                        <td>{ROLE_LABEL[r.role] ?? r.role}</td>
                        <td>{r.people}{r.assumedPerson && ' (assumed)'}</td>
                        <td>{one(r.effortP50)} – {one(r.effortP85)}</td>
                        <td>{one(r.weeksP50)} – {one(r.weeksP85)}</td>
                        {s.costP50 != null && <td>{r.costP50 != null && r.costP85 != null && s.currency ? `${money(s.currency, r.costP50)} – ${money(s.currency, r.costP85)}` : '—'}</td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {s.notes.map((n) => <p key={n} className="small">{n}</p>)}
            </div>
          )}

          {version.coverage.length > 0 && (
            <Callout tone={gaps.every((g) => g.accepted) ? 'success' : 'warning'}
              title={`Design coverage: ${version.coverage.length - gaps.length} of ${version.coverage.length} design items have estimated work`}>
              {gaps.length === 0 ? 'Every design item is covered by at least one work item.' : (
                <ul style={{ margin: '0.35rem 0 0', paddingLeft: '1.1rem' }}>
                  {gaps.map((g) => (
                    <li key={g.designKey}>
                      {g.title}{' '}
                      {g.accepted ? <span className="small">(accepted without work)</span> : review.canAdjust && (
                        <button type="button" className="btn-ghost btn-sm" disabled={busy}
                          onClick={() => void act(`gaps/${encodeURIComponent(g.designKey)}/accept`, {}, 'Accepted without estimated work.')}>
                          Accept without work
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Callout>
          )}

          {PHASES.filter((p) => version.items.some((i) => i.phase === p)).map((phase) => (
            <section key={phase} aria-label={PHASE_LABEL[phase]}>
              <h3>{PHASE_LABEL[phase]} <span className="small">({version.items.filter((i) => i.phase === phase).length})</span></h3>
              <ul className="brd-items">
                {version.items.filter((i) => i.phase === phase).map((item) => (
                  <li key={item.itemKey} className={item.flags.length > 0 && !item.accepted && item.adjustments.length === 0 ? 'support-flagged' : ''}>
                    <span className="label">{ROLE_LABEL[item.role] ?? item.role}</span> <strong>{item.title}</strong>
                    <div style={{ margin: '0.2rem 0' }}>{item.detail}</div>
                    <div className="small">
                      {one(item.low)} / {one(item.likely)} / {one(item.high)} days (low / likely / high) · expected {one(item.expected)}
                      {item.dependsOn.length > 0 && ` · after: ${item.dependsOn.join('; ')}`}
                    </div>
                    {item.adjustments.slice(-1).map((a) => (
                      <div key={a.at} className="small">
                        Adjusted by {a.by} from {one(item.proposedLow)} / {one(item.proposedLikely)} / {one(item.proposedHigh)}:
                        {' '}“{a.reason}”
                      </div>
                    ))}
                    {item.assumptions.length > 0 && <div className="small">Assumes: {item.assumptions.join('; ')}</div>}
                    {item.risk && <div className="small">Risk: {item.risk}</div>}
                    {item.flags.map((f) => <div key={f}><Badge tone="warning">{FLAG_LABEL[f] ?? f}</Badge></div>)}
                    {review.canAdjust && adjusting !== item.itemKey && (
                      <div className="row" style={{ marginTop: '0.3rem' }}>
                        <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setAdjusting(item.itemKey)}>Adjust</button>
                        {item.flags.length > 0 && !item.accepted && item.adjustments.length === 0 && (
                          <button type="button" className="btn-ghost btn-sm" disabled={busy}
                            onClick={() => void act(`flags/${encodeURIComponent(item.itemKey)}/accept`, {}, 'Item kept as proposed.')}>
                            Keep as proposed
                          </button>
                        )}
                      </div>
                    )}
                    {adjusting === item.itemKey && (
                      <AdjustForm item={item} busy={busy} onCancel={() => setAdjusting(null)}
                        onSave={(low, likely, high, reason) => void act(`items/${encodeURIComponent(item.itemKey)}/adjust`,
                          { low, likely, high, reason }, 'Adjustment saved.')} />
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}

          {version.assumptions.length > 0 && (
            <section aria-label="Assumptions"><h3>Assumptions</h3>
              <ul className="brd-items">{version.assumptions.map((a) => <li key={a}>{a}</li>)}</ul></section>
          )}
          {version.excluded.length > 0 && (
            <section aria-label="Not included"><h3>Not included</h3>
              <ul className="brd-items">{version.excluded.map((a) => <li key={a}>{a}</li>)}</ul></section>
          )}

          <div className="stack" style={{ marginTop: '1rem' }}>
            {review.approvedBy && (
              <p className="small">Approved by {review.approvedBy} · {formatDateTime(review.approvedAt)}{review.approvalNote && ` · ${review.approvalNote}`}</p>
            )}
            {review.stage === 'IN_REVIEW' && review.blockedReason && <Callout tone="info">{review.blockedReason}</Callout>}
            {review.stage === 'IN_REVIEW' && !review.isApprover && (
              <p className="small">A BRD Approver of the project approves the estimate once every flag and gap is decided.</p>
            )}
            <div className="row">
              {review.canApprove && (
                <button type="button" className="btn-primary" disabled={busy}
                  onClick={() => { setAloneConfirmed(false); setApproving(true); }}>Approve estimate</button>
              )}
              {review.canRequestChanges && !changesOpen && (
                <button type="button" className="btn-secondary" disabled={busy} onClick={() => setChangesOpen(true)}>Request a new version</button>
              )}
            </div>
            {review.canRequestChanges && changesOpen && (
              <form className="stack" onSubmit={(e) => {
                e.preventDefault();
                void act('request-changes', { text: text.trim() }, 'Requested. The Estimation agent is revising the estimate.');
              }}>
                <label>
                  What should change? (10 to 5000 characters; everything else is kept)
                  <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={3} />
                </label>
                <div className="row">
                  <button type="submit" disabled={busy || text.trim().length < 10}>Send</button>
                  <button type="button" className="btn-ghost" onClick={() => setChangesOpen(false)}>Cancel</button>
                </div>
              </form>
            )}
          </div>
          <div style={{ marginTop: '1.25rem' }}>
            <ActivityTimeline requirementId={requirementId} stage="ESTIMATE" live={false} title="How this estimate was produced" />
          </div>
        </>
      )}

      {data.canSee && data.history.length > 1 && (
        <details style={{ marginTop: '1.25rem' }}>
          <summary><strong>Version history</strong> <span className="small">({data.history.length} versions)</span></summary>
          <div className="table-wrap" style={{ marginTop: '0.75rem' }}>
            <table>
              <thead><tr><th>Version</th><th>Status</th><th>Produced</th><th>Requested change</th><th>Approved by</th><th aria-label="Downloads" /></tr></thead>
              <tbody>
                {data.history.map((h) => (
                  <tr key={h.versionNo}>
                    <td>v{h.versionNo}</td>
                    <td><Badge tone={h.status === 'APPROVED' ? 'success' : 'neutral'}>{h.status.replace('_', ' ').toLowerCase()}</Badge></td>
                    <td>{formatDateTime(h.createdAt)}</td>
                    <td>{h.changeRequest ?? '—'}</td>
                    <td>{h.approvedBy ? `${h.approvedBy} · ${formatDateTime(h.approvedAt)}` : '—'}</td>
                    <td>{h.approvedBy && (
                      <span className="row" style={{ flexWrap: 'nowrap' }}>
                        <a className="btn btn-ghost btn-sm" href={exportUrl(requirementId, 'pdf', h.versionNo)} download>PDF</a>
                        <a className="btn btn-ghost btn-sm" href={exportUrl(requirementId, 'csv', h.versionNo)} download>CSV</a>
                      </span>
                    )}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}

      {approving && version && (
        <Modal title="Approve the estimate" description={`Version ${version.versionNo}, based on approved design version ${version.designVersionNo}.`}
          onClose={() => setApproving(false)}
          footer={
            <>
              <button type="button" className="btn-secondary" onClick={() => setApproving(false)}>Cancel</button>
              <button type="button" className="btn-primary" disabled={!aloneConfirmed || busy} onClick={() => {
                setApproving(false);
                void act('approve', { expectedReviewHash: version.reviewHash, confirmApproveAlone: true }, 'Estimate approved.');
              }}>Approve estimate</button>
            </>
          }>
          <p>
            You are approving these figures, including every adjustment shown. No second reviewer checks them, and the
            approval is recorded in the audit trail with this confirmation.
          </p>
          <label className="check">
            <input type="checkbox" checked={aloneConfirmed} onChange={(e) => setAloneConfirmed(e.target.checked)} />
            <span>I have reviewed this estimate and approve it on my own, without a second reviewer.</span>
          </label>
        </Modal>
      )}
      {message && <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>}
    </FoldSection>
  );
}
