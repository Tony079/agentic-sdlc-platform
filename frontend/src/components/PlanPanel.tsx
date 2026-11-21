import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Badge, Callout, Modal, formatDateTime, type Tone } from '../ui/components';
import { ActivityTimeline } from './ActivityTimeline';
import { FoldSection } from '../ui/Fold';

// GD-34: the Delivery plan stage of a requirement.

const ROLES = ['FRONTEND', 'BACKEND', 'DATA', 'DEVOPS', 'QA', 'DESIGN', 'OTHER'] as const;
type Role = (typeof ROLES)[number];
const ROLE_LABEL: Record<string, string> = {
  FRONTEND: 'Frontend', BACKEND: 'Backend', DATA: 'Data', DEVOPS: 'DevOps', QA: 'QA', DESIGN: 'Design', OTHER: 'Other',
};
const PRIORITY_TONE: Record<string, Tone> = { MUST: 'danger', SHOULD: 'warning', COULD: 'neutral' };
const FLAG_LABEL: Record<string, string> = {
  NO_ESTIMATE: 'Not linked to any estimated work: its effort is 0',
  NO_CRITERIA: 'No acceptance criteria',
  NO_EPIC: 'Its epic was not found; placed in the first epic',
  OVERSIZED: 'Larger than one sprint for this role: consider splitting it',
  CYCLE: 'Part of a dependency cycle',
  BEFORE_DEPENDENCY: 'Pinned to a sprint before a story it depends on',
};

interface Settings { sprintWeeks: number; startDate: string; daysPerWeek: number; team: Partial<Record<Role, number>> }
interface Edit { changes: Record<string, unknown>; reason: string; by: string; at: string }
interface Story {
  storyKey: string; ordinal: number; epicKey: string; title: string; acceptanceCriteria: string[]; estimateRefs: string[];
  brdRefs: string[]; role: string; priority: string; dependsOn: string[]; effortDays: number; sprint: number | null;
  pinned: boolean; flags: string[]; accepted: boolean; edits: Edit[];
}
interface Epic { epicKey: string; title: string; goal: string; stories: number; effortDays: number }
interface Gap { key: string; kind: 'ESTIMATE_ITEM' | 'BRD_CRITERION'; title: string; accepted: boolean }
interface Sprint {
  number: number; start: string; end: string; load: Record<string, number>; capacity: Record<string, number>;
  overCapacity: boolean; effortDays: number;
}
interface PlanVersion {
  id: string; versionNo: number; status: string; estimateVersionNo: number; reviewHash: string | null;
  changeRequest: string | null; failureCode: string | null; attempts: number; createdAt: string; settings: Settings;
  risks: string[]; epics: Epic[]; stories: Story[]; sprints: Sprint[]; gaps: Gap[]; totalEffortDays: number;
  estimateEffortDays: number | null; notes: string[];
}
type Stage = 'ESTIMATE_NOT_APPROVED' | 'NOT_STARTED' | 'GENERATING' | 'FAILED' | 'IN_REVIEW' | 'APPROVED';
interface PlanReview {
  stage: Stage; isEstimator: boolean; isApprover: boolean; canStart: boolean; canRetry: boolean; canEdit: boolean;
  canApprove: boolean; canRequestChanges: boolean; unacceptedFlags: string[]; openGaps: string[];
  approvedBy: string | null; approvedAt: string | null; approvalNote: string | null; estimateOutdated: boolean;
  blockedReason: string | null;
}
interface PlanHistory {
  versionNo: number; status: string; createdAt: string; changeRequest: string | null; approvedBy: string | null;
  approvedAt: string | null;
}
interface PlanPush {
  id: string; repository: string; planVersionNo: number; status: 'QUEUED' | 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED';
  itemsTotal: number; created: number; updated: number; failed: number; failureCode: string | null; message: string;
  requestedAt: string; finishedAt: string | null;
}
interface PushInfo {
  canPush: boolean; unavailableReason: string | null; repositories: { id: string; fullName: string }[];
  pushes: PlanPush[]; issueUrls: Record<string, string>;
}
interface PlanState {
  requirementId: string; canSee: boolean; insider: boolean; review: PlanReview; version: PlanVersion | null;
  history: PlanHistory[]; defaults: Settings | null; push: PushInfo | null;
}

const PUSH_TONE: Record<PlanPush['status'], Tone> = {
  QUEUED: 'info', RUNNING: 'info', DONE: 'success', PARTIAL: 'warning', FAILED: 'danger',
};
const pushing = (p: PushInfo | null | undefined) => p?.pushes.some((x) => x.status === 'QUEUED' || x.status === 'RUNNING') ?? false;
const issueNumber = (url: string) => url.split('/').pop();

/** GD-34 phase 2: push the approved plan to GitHub Issues (Estimators), and the status of recent pushes (everyone). */
function PushSection({ push, busy, onPush }: { push: PushInfo; busy: boolean; onPush: (repositoryId: string) => void }) {
  const [repositoryId, setRepositoryId] = useState(push.repositories[0]?.id ?? '');
  const running = pushing(push);
  return (
    <section aria-label="GitHub Issues" className="stack" style={{ marginTop: '1rem' }}>
      <h3>GitHub Issues</h3>
      {push.canPush && (
        <>
          <p className="small">
            Each story becomes an issue with its acceptance criteria as a checklist; each sprint becomes a milestone with its
            end date; epics, roles and priorities become labels. Pushing again updates the same issues, and criteria ticked
            on GitHub stay ticked.
          </p>
          <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label>Repository
              <select value={repositoryId} onChange={(e) => setRepositoryId(e.target.value)}>
                {push.repositories.map((r) => <option key={r.id} value={r.id}>{r.fullName}</option>)}
              </select>
            </label>
            <button type="button" className="btn-primary" disabled={busy || running || !repositoryId}
              onClick={() => onPush(repositoryId)}>{push.pushes.length > 0 ? 'Push again' : 'Push to GitHub Issues'}</button>
          </div>
        </>
      )}
      {push.unavailableReason && <Callout tone="info" title="Not available yet">{push.unavailableReason}</Callout>}
      {push.pushes.length > 0 && (
        <ul className="brd-items">
          {push.pushes.map((p) => (
            <li key={p.id}>
              <Badge tone={PUSH_TONE[p.status]}>{p.status.toLowerCase()}</Badge> <strong>{p.repository}</strong>{' '}
              <span className="small">plan v{p.planVersionNo} · {formatDateTime(p.requestedAt)}</span>
              <div className="small">{p.message}</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const STAGE: Record<Stage, { label: string; tone: Tone }> = {
  ESTIMATE_NOT_APPROVED: { label: 'Waiting for the estimate', tone: 'neutral' },
  NOT_STARTED: { label: 'Waiting for planning', tone: 'warning' },
  GENERATING: { label: 'Being written', tone: 'info' },
  FAILED: { label: 'Failed', tone: 'danger' },
  IN_REVIEW: { label: 'In review', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
};

const one = (n: number) => (Math.round(n * 10) / 10).toLocaleString();
const day = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });

function exportUrl(requirementId: string, format: 'pdf' | 'docx' | 'csv'): string {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return `/api/v1/requirements/${requirementId}/plan/export?format=${format}&tz=${encodeURIComponent(tz)}`;
}

const isMonday = (iso: string) => new Date(`${iso}T00:00:00`).getDay() === 1;

function SettingsForm({ initial, busy, onStart, submitLabel = 'Start delivery plan', intro, onCancel }: {
  initial: Settings; busy: boolean; onStart: (s: Settings) => void; submitLabel?: string; intro?: string; onCancel?: () => void;
}) {
  const [s, setS] = useState<Settings>(initial);
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); onStart(s); }}>
      <p className="small">
        {intro ?? 'The Planning agent writes epics and user stories; Garuda gives each story its effort from the approved '
          + 'estimate and lays the stories out in sprints for this team. These settings are remembered for the project.'}
      </p>
      <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
        <label>Sprint length (weeks)
          <input type="number" min={1} max={4} value={s.sprintWeeks} onChange={(e) => setS({ ...s, sprintWeeks: Number(e.target.value) })} style={{ width: '5rem' }} />
        </label>
        <label>First sprint starts
          <input type="date" value={s.startDate} onChange={(e) => setS({ ...s, startDate: e.target.value })} />
        </label>
        <label>Working days per week
          <input type="number" min={1} max={7} value={s.daysPerWeek} onChange={(e) => setS({ ...s, daysPerWeek: Number(e.target.value) })} style={{ width: '5rem' }} />
        </label>
      </div>
      <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
        {ROLES.map((role) => (
          <label key={role}>{ROLE_LABEL[role]} people
            <input type="number" min={0} max={50} aria-label={`${ROLE_LABEL[role]} people`} value={s.team[role] ?? 0}
              onChange={(e) => setS({ ...s, team: { ...s.team, [role]: Number(e.target.value) } })} style={{ width: '5rem' }} />
          </label>
        ))}
      </div>
      <p className="small">
        Each role can take {s.daysPerWeek} × {s.sprintWeeks} = {s.daysPerWeek * s.sprintWeeks} days per person per sprint;
        a story bigger than that is flagged for splitting.
        {s.startDate && !isMonday(s.startDate) && ' The first sprint does not start on a Monday.'}
      </p>
      <div className="row">
        <button type="submit" className="btn-primary" disabled={busy}>{submitLabel}</button>
        {onCancel && <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}

function EditForm({ story, busy, onSave, onCancel }: {
  story: Story; busy: boolean; onSave: (changes: Record<string, unknown>, reason: string) => void; onCancel: () => void;
}) {
  const [title, setTitle] = useState(story.title);
  const [criteria, setCriteria] = useState(story.acceptanceCriteria.join('\n'));
  const [priority, setPriority] = useState(story.priority);
  const [sprint, setSprint] = useState(story.sprint ?? 1);
  const [reason, setReason] = useState('');
  return (
    <form className="stack" style={{ marginTop: '0.5rem' }} onSubmit={(e) => {
      e.preventDefault();
      const changes: Record<string, unknown> = {};
      if (title.trim() !== story.title) changes.title = title.trim();
      const lines = criteria.split('\n').map((l) => l.trim()).filter(Boolean);
      if (lines.join('\n') !== story.acceptanceCriteria.join('\n')) changes.acceptanceCriteria = lines;
      if (priority !== story.priority) changes.priority = priority;
      if (sprint !== story.sprint) changes.sprint = sprint;
      onSave(changes, reason.trim());
    }}>
      <label>Story<input value={title} maxLength={300} onChange={(e) => setTitle(e.target.value)} /></label>
      <label>Acceptance criteria (one per line)<textarea value={criteria} rows={3} onChange={(e) => setCriteria(e.target.value)} /></label>
      <div className="row" style={{ gap: '0.75rem' }}>
        <label>Priority
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="MUST">Must</option><option value="SHOULD">Should</option><option value="COULD">Could</option>
          </select>
        </label>
        <label>Sprint<input type="number" min={1} value={sprint} onChange={(e) => setSprint(Number(e.target.value))} style={{ width: '5rem' }} /></label>
      </div>
      <label>Reason (10 to 1000 characters; shown with the plan)<textarea value={reason} rows={2} maxLength={1000} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="row">
        <button type="submit" className="btn-primary btn-sm" disabled={busy || reason.trim().length < 10}>Save changes</button>
        <button type="button" className="btn-ghost btn-sm" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/**
 * Delivery plan (GD-34): after estimate approval, Estimators start, review and edit the plan; a BRD Approver approves
 * it. Drafts are for Estimators and BRD Approvers; the approved plan is for the whole project.
 */
export function PlanPanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const plan = useQuery({
    queryKey: ['plan', requirementId],
    queryFn: () => api.get<PlanState>(`/api/v1/requirements/${requirementId}/plan`),
    refetchInterval: (q) => (q.state.data?.review.stage === 'GENERATING' || pushing(q.state.data?.push) ? 4000 : false),
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [changesOpen, setChangesOpen] = useState(false);
  const [text, setText] = useState('');
  const [approving, setApproving] = useState(false);
  const [aloneConfirmed, setAloneConfirmed] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  async function act(path: string, body: unknown, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      const next = await api.post<PlanState>(`/api/v1/requirements/${requirementId}/plan/${path}`, body);
      queryClient.setQueryData(['plan', requirementId], next);
      setMessage({ tone: 'info', text: done });
      setEditing(null);
      setChangesOpen(false);
      setSettingsOpen(false);
      setText('');
      await queryClient.invalidateQueries({ queryKey: ['notifications'] });
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'The action failed.' });
    } finally {
      setBusy(false);
    }
  }

  const data = plan.data;
  if (plan.isPending) return <section aria-label="Delivery plan"><h2>Delivery plan</h2><p>Loading…</p></section>;
  if (!data) return null;
  const { review, version } = data;
  const stage = STAGE[review.stage];
  const showContent = data.canSee && version && version.status !== 'GENERATING' && version.status !== 'FAILED';
  const epicTitle = (key: string) => version?.epics.find((e) => e.epicKey === key)?.title ?? '';
  const openGaps = version?.gaps.filter((g) => !g.accepted) ?? [];
  // Undecided flags by kind, so an Estimator can keep every story with the same flag in one go.
  const undecided: Record<string, number> = {};
  version?.stories.filter((st) => st.flags.length > 0 && !st.accepted && st.edits.length === 0)
    .forEach((st) => st.flags.forEach((f) => { undecided[f] = (undecided[f] ?? 0) + 1; }));

  return (
    <FoldSection
      label="Delivery plan"
      title="Delivery plan"
      foldKey={`fold:${requirementId}:plan`}
      summary={showContent && version ? `${version.stories.length} stories · ${version.sprints.length} sprints` : undefined}
      actions={
        <>
          {data.canSee && review.stage === 'APPROVED' && (
            <>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'csv')} download>Spreadsheet</a>
              <a className="btn btn-secondary btn-sm" href={exportUrl(requirementId, 'pdf')} download>PDF</a>
            </>
          )}
          <Badge tone={stage.tone}>{stage.label}</Badge>
        </>
      }
    >
      {review.stage === 'ESTIMATE_NOT_APPROVED' && <p className="small">The delivery plan starts after the estimate is approved.</p>}
      {!data.canSee && review.stage !== 'ESTIMATE_NOT_APPROVED' && !review.canStart && (
        <p className="small">{review.blockedReason ?? 'The plan is being prepared. It is shown to the project once a BRD Approver approves it.'}</p>
      )}
      {review.canStart && (
        <SettingsForm initial={data.defaults ?? { sprintWeeks: 2, startDate: new Date().toISOString().slice(0, 10), daysPerWeek: 5, team: {} }}
          busy={busy} onStart={(settings) => void act('start', { settings }, 'The Planning agent has started. You are notified when the plan is ready.')} />
      )}
      {data.canSee && review.stage === 'GENERATING' && (
        <ActivityTimeline requirementId={requirementId} stage="PLAN" live title="What the Planning agent is doing" />
      )}
      {data.canSee && review.stage === 'FAILED' && version && (
        <>
          <Callout tone="danger" title="The plan could not be written">
            {review.canRetry ? `You can try again (${version.attempts} of 3 attempts used).` : 'All three attempts are used. Contact Garuda support.'}
          </Callout>
          {review.canRetry && (
            <button type="button" className="btn-primary" disabled={busy}
              onClick={() => void act('retry', {}, 'Trying again. You are notified when the plan is ready.')}>Try again</button>
          )}
        </>
      )}
      {review.estimateOutdated && version && (
        <Callout tone="warning" title="Based on an older estimate">
          This plan was made for estimate version {version.estimateVersionNo}. Start a new plan to include the approved changes.
        </Callout>
      )}

      {showContent && version && (
        <>
          <p className="small">
            Version {version.versionNo} · based on approved estimate version {version.estimateVersionNo} · produced{' '}
            {formatDateTime(version.createdAt)}{version.changeRequest && ` · requested change: “${version.changeRequest}”`}
          </p>
          <div className="row" style={{ gap: '1.5rem', flexWrap: 'wrap' }}>
            <div><div className="label">Stories</div><strong>{version.stories.length}</strong><div className="small">in {version.epics.length} epics</div></div>
            <div><div className="label">Sprints</div><strong>{version.sprints.length}</strong>
              <div className="small">{(() => {
                const first = version.sprints[0];
                const last = version.sprints[version.sprints.length - 1];
                return first && last ? `${day(first.start)} – ${day(last.end)}` : '';
              })()}</div></div>
            <div><div className="label">Effort (person-days)</div><strong>{one(version.totalEffortDays)}</strong>
              <div className="small">{version.estimateEffortDays != null ? `estimate: ${one(version.estimateEffortDays)}` : 'from the approved estimate'}</div></div>
          </div>
          {version.notes.map((n) => <p key={n} className="small">{n}</p>)}
          {review.canEdit && !settingsOpen && (
            <div className="row">
              <button type="button" className="btn-secondary btn-sm" disabled={busy} onClick={() => setSettingsOpen(true)}>
                Change sprint settings
              </button>
              <span className="small">
                {version.settings.sprintWeeks}-week sprints from {day(version.settings.startDate)},{' '}
                {Object.entries(version.settings.team).filter(([, n]) => (n ?? 0) > 0).map(([r, n]) => `${n} ${ROLE_LABEL[r] ?? r}`).join(', ')}
              </span>
            </div>
          )}
          {review.canEdit && settingsOpen && (
            <SettingsForm initial={version.settings} busy={busy} submitLabel="Apply new sprint settings"
              intro="Garuda makes a new version with the same stories and your edits, and lays out the sprints again. No new writing by the agent; decisions on flags are made again because the flags change with the sprints."
              onCancel={() => setSettingsOpen(false)}
              onStart={(settings) => void act('settings', { settings }, 'Laying out the sprints again with the same stories.')} />
          )}
          {review.canEdit && Object.entries(undecided).some(([, n]) => n > 1) && (
            <Callout tone="warning" title="Decide flags in bulk">
              <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
                {Object.entries(undecided).filter(([, n]) => n > 1).map(([flag, n]) => (
                  <button key={flag} type="button" className="btn-secondary btn-sm" disabled={busy}
                    onClick={() => void act('flags/accept-all', { flag }, `${n} stories kept as proposed.`)}>
                    Keep all {n} as proposed: {FLAG_LABEL[flag] ?? flag}
                  </button>
                ))}
              </div>
            </Callout>
          )}

          {data.insider && version.gaps.length > 0 && (
            <Callout tone={openGaps.length === 0 ? 'success' : 'warning'} title={`${version.gaps.length} planning gap(s)`}>
              <ul style={{ margin: '0.35rem 0 0', paddingLeft: '1.1rem' }}>
                {version.gaps.map((g) => (
                  <li key={g.key}>
                    <span className="small">{g.kind === 'ESTIMATE_ITEM' ? 'Estimated work not in any story' : 'BRD acceptance criterion not traced'}:</span> {g.title}{' '}
                    {g.accepted ? <span className="small">(accepted)</span> : review.canEdit && (
                      <button type="button" className="btn-ghost btn-sm" disabled={busy}
                        onClick={() => void act(`gaps/${encodeURIComponent(g.key)}/accept`, {}, 'Gap accepted.')}>Accept</button>
                    )}
                  </li>
                ))}
              </ul>
            </Callout>
          )}

          {version.sprints.map((sp) => (
            <section key={sp.number} aria-label={`Sprint ${sp.number}`}>
              <h3>
                Sprint {sp.number} <span className="small">{day(sp.start)} – {day(sp.end)} · {one(sp.effortDays)} person-days</span>
                {sp.overCapacity && <> <Badge tone="warning">Over capacity</Badge></>}
              </h3>
              <p className="small">
                {Object.entries(sp.load).map(([role, load]) => `${ROLE_LABEL[role] ?? role} ${one(load)} of ${one(sp.capacity[role] ?? 0)} days`).join(' · ')}
              </p>
              <ul className="brd-items">
                {version.stories.filter((s) => s.sprint === sp.number).map((s) => (
                  <li key={s.storyKey} className={s.flags.length > 0 && !s.accepted && s.edits.length === 0 ? 'support-flagged' : ''}>
                    <Badge tone={PRIORITY_TONE[s.priority] ?? 'neutral'}>{s.priority.toLowerCase()}</Badge>{' '}
                    <span className="label">{ROLE_LABEL[s.role] ?? s.role}</span> <strong>{s.title}</strong>
                    {(() => {
                      const url = data.push?.issueUrls[s.storyKey];
                      return url ? <> <a href={url} target="_blank" rel="noopener noreferrer" className="small">#{issueNumber(url)}</a></> : null;
                    })()}
                    <div className="small">{epicTitle(s.epicKey)} · {one(s.effortDays)} days{s.pinned && ' · sprint set by an Estimator'}
                      {s.dependsOn.length > 0 && ` · after: ${s.dependsOn.join('; ')}`}</div>
                    <ul className="small" style={{ margin: '0.2rem 0 0', paddingLeft: '1.1rem' }}>
                      {s.acceptanceCriteria.map((c) => <li key={c}>{c}</li>)}
                    </ul>
                    {s.edits.slice(-1).map((e) => <div key={e.at} className="small">Edited by {e.by}: “{e.reason}”</div>)}
                    {s.flags.map((f) => <div key={f}><Badge tone="warning">{FLAG_LABEL[f] ?? f}</Badge></div>)}
                    {review.canEdit && editing !== s.storyKey && (
                      <div className="row" style={{ marginTop: '0.3rem' }}>
                        <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setEditing(s.storyKey)}>Edit</button>
                        {s.flags.length > 0 && !s.accepted && s.edits.length === 0 && (
                          <button type="button" className="btn-ghost btn-sm" disabled={busy}
                            onClick={() => void act(`flags/${encodeURIComponent(s.storyKey)}/accept`, {}, 'Story kept as proposed.')}>Keep as proposed</button>
                        )}
                      </div>
                    )}
                    {editing === s.storyKey && (
                      <EditForm story={s} busy={busy} onCancel={() => setEditing(null)}
                        onSave={(changes, reason) => void act(`stories/${encodeURIComponent(s.storyKey)}/edit`, { changes, reason }, 'Story updated.')} />
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}

          {version.risks.length > 0 && (
            <section aria-label="Delivery risks"><h3>Delivery risks</h3><ul className="brd-items">{version.risks.map((r) => <li key={r}>{r}</li>)}</ul></section>
          )}

          <div className="stack" style={{ marginTop: '1rem' }}>
            {review.approvedBy && (
              <p className="small">Approved by {review.approvedBy} · {formatDateTime(review.approvedAt)}{review.approvalNote && ` · ${review.approvalNote}`}</p>
            )}
            {review.stage === 'IN_REVIEW' && review.blockedReason && <Callout tone="info">{review.blockedReason}</Callout>}
            <div className="row">
              {review.canApprove && (
                <button type="button" className="btn-primary" disabled={busy} onClick={() => { setAloneConfirmed(false); setApproving(true); }}>Approve plan</button>
              )}
              {review.canRequestChanges && !changesOpen && (
                <button type="button" className="btn-secondary" disabled={busy} onClick={() => setChangesOpen(true)}>Request a new version</button>
              )}
            </div>
            {review.canRequestChanges && changesOpen && (
              <form className="stack" onSubmit={(e) => { e.preventDefault(); void act('request-changes', { text: text.trim() }, 'Requested. The Planning agent is revising the plan.'); }}>
                <label>What should change? (10 to 5000 characters; everything else is kept)
                  <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={5000} rows={3} />
                </label>
                <div className="row">
                  <button type="submit" disabled={busy || text.trim().length < 10}>Send</button>
                  <button type="button" className="btn-ghost" onClick={() => setChangesOpen(false)}>Cancel</button>
                </div>
              </form>
            )}
          </div>
          {review.stage === 'APPROVED' && data.push && (
            <PushSection push={data.push} busy={busy}
              onPush={(repositoryId) => void act('push', { repositoryId }, 'Pushing to GitHub. This page updates as issues are created.')} />
          )}
          {data.insider && (
            <div style={{ marginTop: '1.25rem' }}>
              <ActivityTimeline requirementId={requirementId} stage="PLAN" live={false} title="How this plan was produced" />
            </div>
          )}
        </>
      )}

      {data.insider && data.history.length > 1 && (
        <details style={{ marginTop: '1.25rem' }}>
          <summary><strong>Version history</strong> <span className="small">({data.history.length} versions)</span></summary>
          <ul className="brd-items">
            {data.history.map((h) => (
              <li key={h.versionNo}>v{h.versionNo} · {h.status.toLowerCase()} · {formatDateTime(h.createdAt)}
                {h.changeRequest && ` · “${h.changeRequest}”`}{h.approvedBy && ` · approved by ${h.approvedBy}`}</li>
            ))}
          </ul>
        </details>
      )}

      {approving && version && (
        <Modal title="Approve the delivery plan" description={`Version ${version.versionNo}, based on approved estimate version ${version.estimateVersionNo}.`}
          onClose={() => setApproving(false)}
          footer={
            <>
              <button type="button" className="btn-secondary" onClick={() => setApproving(false)}>Cancel</button>
              <button type="button" className="btn-primary" disabled={!aloneConfirmed || busy} onClick={() => {
                setApproving(false);
                void act('approve', { expectedReviewHash: version.reviewHash, confirmApproveAlone: true }, 'Plan approved. It is now visible to the project.');
              }}>Approve plan</button>
            </>
          }>
          <p>You are approving this plan, including every edit shown. Once approved it is visible to the whole project.</p>
          <label className="check">
            <input type="checkbox" checked={aloneConfirmed} onChange={(e) => setAloneConfirmed(e.target.checked)} />
            <span>I have reviewed this plan and approve it on my own, without a second reviewer.</span>
          </label>
        </Modal>
      )}
      {message && <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>}
    </FoldSection>
  );
}
