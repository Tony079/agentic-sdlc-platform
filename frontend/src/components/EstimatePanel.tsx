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
// GD-35: agent delivery mode.
const AGENT_FLAG_LABEL: Record<string, string> = {
  CLIPPED: 'Figures were out of range and were corrected',
  UNCERTAIN: 'Wide review range: the high figure is more than five times the low one',
  LARGE: 'More than 16 agent hours: split it into smaller agent tasks',
};
type Mode = 'AGENT' | 'HUMAN';

interface Settings {
  team: Partial<Record<Role, number>>;
  daysPerWeek: number;
  contingencyPercent: number;
  currency: string | null;
  dayRates: Partial<Record<Role, number>> | null;
  deliveryMode?: Mode;
  agentSlots?: number;
  agentCostPerHour?: number | null;
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
  kind?: 'WORK' | 'EXTERNAL';
  agentHours?: number | null;
  leadDays?: number | null;
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
  deliveryMode?: Mode; agentHours?: number; waves?: number; external?: number; longestLeadDays?: number;
  aiCost?: number | null;
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
  const [s, setS] = useState<Settings>({ ...initial, deliveryMode: initial.deliveryMode ?? 'AGENT', agentSlots: initial.agentSlots ?? 4 });
  const [priced, setPriced] = useState((!!initial.dayRates && Object.keys(initial.dayRates).length > 0) || !!initial.agentCostPerHour);
  const agent = s.deliveryMode === 'AGENT';
  const setTeam = (role: Role, n: number) => setS({ ...s, team: { ...s.team, [role]: n } });
  const setRate = (role: Role, n: number) => setS({ ...s, dayRates: { ...(s.dayRates ?? {}), [role]: n } });
  return (
    <form className="stack" onSubmit={(e) => {
      e.preventDefault();
      const sent = agent ? s : { ...s, agentSlots: undefined, agentCostPerHour: undefined };
      onStart(priced ? sent : { ...sent, currency: null, dayRates: null, agentCostPerHour: null });
    }}>
      <fieldset className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label">Who builds it</legend>
        <label className="check">
          <input type="radio" name="deliveryMode" checked={agent} onChange={() => setS({ ...s, deliveryMode: 'AGENT' })} />
          <span><strong>Coding agents, reviewed by people</strong> (recommended). Each work item is one agent task; the
            estimate counts agent hours, people’s review time and outside work (approvals, accounts, suppliers).</span>
        </label>
        <label className="check">
          <input type="radio" name="deliveryMode" checked={!agent} onChange={() => setS({ ...s, deliveryMode: 'HUMAN' })} />
          <span><strong>A human team</strong>: person-days of development per role.</span>
        </label>
      </fieldset>
      <p className="small">
        The Estimation agent proposes the work items and their effort; Garuda computes the totals, the timeline for
        {agent ? ' these reviewers and coding agents' : ' this team'} and, with rates, the cost. These settings are
        remembered for the project.
      </p>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Role</th><th>{agent ? 'Reviewers' : 'People'}</th>{priced && <th>Day rate</th>}</tr></thead>
          <tbody>
            {ROLES.map((role) => (
              <tr key={role}>
                <td>{ROLE_LABEL[role]}</td>
                <td>
                  <input type="number" min={0} max={50} aria-label={`${ROLE_LABEL[role]} ${agent ? 'reviewers' : 'people'}`} value={s.team[role] ?? 0}
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
        {agent && (
          <label>
            Coding agents working at once
            <input type="number" min={1} max={50} value={s.agentSlots ?? 4}
              onChange={(e) => setS({ ...s, agentSlots: Number(e.target.value) })} style={{ width: '5rem' }} />
          </label>
        )}
        <label>
          Contingency %
          <input type="number" min={0} max={100} value={s.contingencyPercent}
            onChange={(e) => setS({ ...s, contingencyPercent: Number(e.target.value) })} style={{ width: '5rem' }} />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={priced} onChange={(e) => setPriced(e.target.checked)} />
        <span>Include cost ({agent ? 'reviewers’ day rates and AI cost per agent hour' : 'day rate per role'})</span>
      </label>
      {priced && (
        <label>
          Currency (ISO code, for example INR or USD)
          <input value={s.currency ?? ''} maxLength={3} onChange={(e) => setS({ ...s, currency: e.target.value.toUpperCase() })}
            style={{ width: '6rem' }} />
        </label>
      )}
      {priced && agent && (
        <label>
          AI cost per agent hour (in the currency above)
          <input type="number" min={0} step="0.5" value={s.agentCostPerHour ?? 0}
            onChange={(e) => setS({ ...s, agentCostPerHour: Number(e.target.value) })} style={{ width: '8rem' }} />
        </label>
      )}
      <div className="row">
        <button type="submit" className="btn-primary" disabled={busy}>Start estimate</button>
      </div>
    </form>
  );
}

function AdjustForm({ item, busy, onSave, onCancel, agent = false }: {
  item: EstimateItem; busy: boolean; onSave: (low: number, likely: number, high: number, reason: string) => void;
  onCancel: () => void; agent?: boolean;
}) {
  const unit = agent ? 'review days' : 'days';
  const min = agent ? 0.1 : 0.25;
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
        <label>Low {unit}<input type="number" step={min} min={min} value={low} onChange={(e) => setLow(Number(e.target.value))} style={{ width: '6rem' }} /></label>
        <label>Likely {unit}<input type="number" step={min} min={min} value={likely} onChange={(e) => setLikely(Number(e.target.value))} style={{ width: '6rem' }} /></label>
        <label>High {unit}<input type="number" step={min} min={min} value={high} onChange={(e) => setHigh(Number(e.target.value))} style={{ width: '6rem' }} /></label>
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
  const agent = version?.settings.deliveryMode === 'AGENT';
  const flagLabel = agent ? AGENT_FLAG_LABEL : FLAG_LABEL;

  return (
    <FoldSection
      label="Estimate"
      title="Estimate"
      foldKey={`fold:${requirementId}:estimate`}
      summary={showContent && s ? `Version ${version.versionNo} · ${agent ? `${one(s.agentHours ?? 0)} agent hours, ${range(s.effortWithContingency)} review days` : `${range(s.effortWithContingency)} person-days`}` : undefined}
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
                {agent && (
                  <div><div className="label">Coding agents (hours)</div><strong>{one(s.agentHours ?? 0)}</strong>
                    <div className="small">in {s.waves ?? 0} waves · {version.settings.agentSlots ?? 1} at once</div></div>
                )}
                <div><div className="label">{agent ? 'People’s review (person-days)' : 'Effort (person-days)'}</div><strong>{range(s.effortWithContingency)}</strong>
                  <div className="small">incl. {s.contingencyPercent}% contingency</div></div>
                <div><div className="label">Timeline (weeks)</div><strong>{range(s.weeks)}</strong>
                  <div className="small">{agent ? 'agents, reviewers and outside waits' : 'for the team in the settings'}</div></div>
                {agent && (s.external ?? 0) > 0 && (
                  <div><div className="label">Outside work</div><strong>{s.external}</strong>
                    <div className="small">longest wait {one(s.longestLeadDays ?? 0)} days</div></div>
                )}
                {s.costP50 != null && s.costP85 != null && s.currency && (
                  <div><div className="label">Cost</div><strong>{money(s.currency, s.costP50)} – {money(s.currency, s.costP85)}</strong>
                    <div className="small">{agent && s.aiCost != null ? `incl. ${money(s.currency, s.aiCost)} AI cost` : 'from the day rates'}</div></div>
                )}
              </div>
              <p className="small">
                Ranges are P50 to P85: a 50% and an 85% chance of finishing within them. Garuda computes them from each
                item’s low, likely and high figures; the model only proposes the items.
              </p>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Role</th><th>{agent ? 'Reviewers' : 'People'}</th><th>{agent ? 'Review days' : 'Person-days'}</th><th>Weeks</th>{s.costP50 != null && <th>Cost</th>}</tr></thead>
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
                    {agent && (item.kind === 'EXTERNAL'
                      ? <div><Badge tone="warning">Outside work: a person or supplier, waits about {one(item.leadDays ?? 0)} days</Badge></div>
                      : <div><Badge tone="info">Coding agent · about {one(item.agentHours ?? 0)} hours</Badge></div>)}
                    <div className="small">
                      {one(item.low)} / {one(item.likely)} / {one(item.high)} {agent ? (item.kind === 'EXTERNAL' ? 'days of the owner’s effort' : 'review days') : 'days'} (low / likely / high) · expected {one(item.expected)}
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
                    {item.flags.map((f) => <div key={f}><Badge tone="warning">{flagLabel[f] ?? f}</Badge></div>)}
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
                      <AdjustForm item={item} busy={busy} agent={agent} onCancel={() => setAdjusting(null)}
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
              {review.canRequestChanges && !agent && (
                <button type="button" className="btn-secondary" disabled={busy}
                  onClick={() => void act('request-changes', {
                    text: 'Re-estimate for delivery by coding agents with human review: the same scope, as agent tasks, review time and outside work.',
                    settings: { ...version.settings, deliveryMode: 'AGENT', agentSlots: version.settings.agentSlots ?? 4 },
                  }, 'Re-estimating for coding agents. You are notified when the new version is ready.')}>
                  Re-estimate for coding agents
                </button>
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
