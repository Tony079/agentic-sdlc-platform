import { useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Badge, Callout, formatDateTime, type Tone } from '../ui/components';
import { ActivityLines, ActivityTimeline, type ActivityEntry } from './ActivityTimeline';
import { FoldSection, FoldToggle, useFold } from '../ui/Fold';
import { branchLabel, type BranchOption } from './PlanPanel';

// GD-36: the Development stage. Garuda's Developer agent codes each story of the pushed plan, wave by wave, and opens a
// pull request per story; people review and merge in GitHub.

type TaskStatus = 'WAITING' | 'QUEUED' | 'CODING' | 'PR_OPEN' | 'MERGED' | 'CLOSED' | 'FAILED' | 'SKIPPED' | 'DONE';
interface DevTask {
  id: string; storyKey: string; title: string; wave: number; external: boolean; issueNumber: number | null;
  status: TaskStatus; attempts: number; branch: string | null; prNumber: number | null; prUrl: string | null;
  summary: string | null; testsPassed: boolean | null; filesChanged: number | null; failureCode: string | null;
  reason: string | null; updatedAt: string;
  /** Unfinished work kept on a draft pull request; Retry carries on from it. */
  draft?: boolean;
}
interface DevRun {
  id: string; status: 'RUNNING' | 'PAUSED' | 'DELIVERED' | 'STOPPED'; repository: string; currentWave: number;
  agentSlots: number; startedAt: string; finishedAt: string | null; tasks: DevTask[]; counts: Record<string, number>;
  /** The branch development works from and opens pull requests into; null means the repository's default branch. */
  baseBranch?: string | null;
  /** Branches it may be changed to (for those who control development). */
  branchOptions?: BranchOption[];
  /** Where the branch comes from, in words ("from the QC environment"). */
  branchFrom?: string;
}
interface DevState {
  requirementId: string; canControl: boolean; run: DevRun | null; canStart?: boolean; notStarted?: string | null;
}

const TASK: Record<TaskStatus, { label: string; tone: Tone }> = {
  WAITING: { label: 'waiting', tone: 'neutral' },
  QUEUED: { label: 'queued', tone: 'info' },
  CODING: { label: 'coding', tone: 'info' },
  PR_OPEN: { label: 'pull request open', tone: 'warning' },
  MERGED: { label: 'merged', tone: 'success' },
  CLOSED: { label: 'closed', tone: 'neutral' },
  FAILED: { label: 'needs attention', tone: 'danger' },
  SKIPPED: { label: 'skipped', tone: 'neutral' },
  DONE: { label: 'done', tone: 'success' },
};
const FINISHED: TaskStatus[] = ['MERGED', 'SKIPPED', 'DONE'];
const needsAttention = (t: DevTask) => t.status === 'FAILED' || t.status === 'CLOSED';

/**
 * One story's own log, session by session. Folded by default (open for a story that needs attention); loaded when
 * opened, and followed live while the story is being coded, whose latest step shows even when folded.
 */
function StoryLog({ requirementId, task }: { requirementId: string; task: DevTask }) {
  const coding = task.status === 'CODING' || task.status === 'QUEUED';
  const [open, , setOpen] = useFold(`fold:${requirementId}:log:${task.id}`, needsAttention(task));
  const log = useQuery({
    queryKey: ['development', requirementId, 'log', task.id],
    queryFn: () => api.get<ActivityEntry[]>(`/api/v1/requirements/${requirementId}/development/tasks/${task.id}/log`),
    enabled: open || coding,
    refetchInterval: coding ? 5000 : false,
  });
  const lines = Array.isArray(log.data) ? log.data : [];
  const latest = lines[lines.length - 1];
  return (
    <div className="story-log">
      {coding && latest && !open && <div className="small"><span className="spinner" aria-hidden="true" /> {latest.message}</div>}
      <details open={open} onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}>
        <summary className="small">{open ? 'Hide log' : 'Show log'}{lines.length > 0 ? ` (${lines.length})` : ''}</summary>
        {open && (log.isPending ? <p className="small">Loading…</p>
          : lines.length === 0 ? <p className="small">Nothing recorded for this story yet.</p>
          : <ActivityLines lines={lines} live={coding} />)}
      </details>
    </div>
  );
}

/**
 * A wave: open while it is current or something in it needs attention; a finished wave folds to one line
 * ("✓ Wave 1 · 2 merged · #64, #65"). Each person's choice is remembered.
 */
function WaveSection({ requirementId, wave, tasks, current, children }: {
  requirementId: string; wave: number; tasks: DevTask[]; current: boolean; children: ReactNode;
}) {
  const finished = tasks.every((t) => FINISHED.includes(t.status));
  const attention = tasks.some(needsAttention);
  const [open, toggle] = useFold(`fold:${requirementId}:wave:${wave}`, current || attention || !finished);
  const merged = tasks.filter((t) => t.status === 'MERGED').length;
  const skipped = tasks.filter((t) => t.status === 'SKIPPED').length;
  const prs = tasks.filter((t) => t.prNumber != null).map((t) => `#${t.prNumber}`).join(', ');
  const summary = finished
    ? `${merged} merged${skipped ? `, ${skipped} skipped` : ''}${prs ? ` · ${prs}` : ''}`
    : `${tasks.filter((t) => FINISHED.includes(t.status)).length} of ${tasks.length} done`;
  return (
    <section aria-label={`Wave ${wave}`} className={open ? 'fold open' : 'fold'}>
      <div className="row fold-header" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
        <FoldToggle open={open} onToggle={toggle} level={3}>
          {finished ? '✓ ' : ''}Wave {wave}
        </FoldToggle>
        {current && !finished && <Badge tone="info">current</Badge>}
        {attention && <Badge tone="danger">needs attention</Badge>}
        <span className="small">{summary}</span>
      </div>
      {open && children}
    </section>
  );
}

const RUN: Record<DevRun['status'], { label: string; tone: Tone }> = {
  RUNNING: { label: 'In development', tone: 'info' },
  PAUSED: { label: 'Paused', tone: 'warning' },
  DELIVERED: { label: 'Delivered', tone: 'success' },
  STOPPED: { label: 'Stopped', tone: 'neutral' },
};

/**
 * Development (GD-36): starts on its own once an approved plan for coding agents is pushed to GitHub. Everyone in the
 * project sees progress; Technical Approvers and Estimators can pause, resume, retry and skip.
 */
export function DevelopmentPanel({ requirementId }: { requirementId: string }) {
  const queryClient = useQueryClient();
  const [starting, setStarting] = useState(false);
  const query = useQuery({
    queryKey: ['development', requirementId],
    queryFn: () => api.get<DevState>(`/api/v1/requirements/${requirementId}/development`),
    refetchInterval: (q) => (q.state.data?.run?.status === 'RUNNING' ? 10000 : starting && !q.state.data?.run ? 3000 : false),
  });
  const [busy, setBusy] = useState(false);
  const [changingBranch, setChangingBranch] = useState(false);
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);

  async function act(path: string, done: string, body: Record<string, unknown> = {}) {
    setBusy(true);
    setMessage(null);
    try {
      const next = await api.post<DevState>(`/api/v1/requirements/${requirementId}/development/${path}`, body);
      queryClient.setQueryData(['development', requirementId], next);
      setMessage({ tone: 'info', text: done });
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ApiError ? e.message : 'The action failed.' });
    } finally {
      setBusy(false);
    }
  }

  const data = query.data;
  if (!data) return null;
  const run = data.run;
  if (!run) {
    return (
      <FoldSection label="Development" title="Development" foldKey={`fold:${requirementId}:development`}
        actions={<Badge tone="neutral">Not started</Badge>}>
        <p className="small">
          {data.notStarted ?? 'Development starts on its own when an approved delivery plan for coding agents is pushed to GitHub Issues.'}
          {' '}Garuda’s Developer agent codes each story and opens a pull request for a person to review and merge.
        </p>
        {data.canStart && (
          <div className="row">
            <button type="button" className="btn-primary" disabled={busy || starting}
              onClick={() => { setStarting(true); void act('start', 'Starting development. The first stories appear here in a moment.'); }}>
              {starting ? 'Starting…' : 'Start development'}
            </button>
          </div>
        )}
        {message && <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>}
      </FoldSection>
    );
  }
  const code = run.tasks.filter((t) => !t.external);
  const outside = run.tasks.filter((t) => t.external);
  const merged = code.filter((t) => t.status === 'MERGED' || t.status === 'SKIPPED').length;
  const waves = [...new Set(code.map((t) => t.wave))].sort((a, b) => a - b);
  const status = RUN[run.status];
  const controls = data.canControl && (run.status === 'RUNNING' || run.status === 'PAUSED');

  const renderTask = (t: DevTask) => (
    <li key={t.id} className={t.status === 'FAILED' ? 'support-flagged' : ''}>
      <Badge tone={TASK[t.status].tone}>{TASK[t.status].label}</Badge> <strong>{t.title}</strong>
      {t.prUrl && <> <a href={t.prUrl} target="_blank" rel="noopener noreferrer" className="small">{t.draft ? 'draft ' : ''}pull request #{t.prNumber}</a></>}
      {!t.prUrl && t.issueNumber != null && <span className="small"> · issue #{t.issueNumber}</span>}
      {t.summary && <div className="small">{t.summary}</div>}
      {(t.testsPassed != null || t.filesChanged != null) && (
        <div className="small">
          {t.filesChanged != null && `${t.filesChanged} files changed`}
          {t.testsPassed != null && ` · tests ${t.testsPassed ? 'passing' : 'not passing'}`}
          {t.attempts > 1 && ` · attempt ${t.attempts}`}
        </div>
      )}
      {t.reason && <div className="small error">{t.draft ? 'Not finished' : 'Not coded'}: {t.reason}.</div>}
      {t.draft && t.status === 'FAILED' && (
        <div className="small">The work so far is on the draft pull request. Retry carries on from it; commits pushed to
          its branch are kept.</div>
      )}
      {!t.external && t.status !== 'WAITING' && <StoryLog requirementId={requirementId} task={t} />}
      {controls && (t.status === 'FAILED' || t.status === 'WAITING' || t.status === 'QUEUED') && (
        <div className="row" style={{ marginTop: '0.3rem' }}>
          {t.status === 'FAILED' && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy}
              onClick={() => void act(`tasks/${t.id}/retry`, t.draft ? 'The Developer agent will carry on from its draft.'
                : 'The Developer agent will try this story again.')}>{t.draft ? 'Retry from draft' : 'Retry'}</button>
          )}
          {!t.external && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy}
              onClick={() => void act(`tasks/${t.id}/skip`, 'Story skipped.')}>Skip</button>
          )}
          {t.external && t.status === 'WAITING' && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy}
              onClick={() => {
                if (window.confirm(`Mark "${t.title}" as done? The waves waiting for it start next; its GitHub issue stays as it is.`)) {
                  void act(`tasks/${t.id}/done`, 'Marked done. Waves waiting for it start within a couple of minutes.');
                }
              }}>Mark done</button>
          )}
        </div>
      )}
    </li>
  );

  return (
    <FoldSection
      label="Development"
      title="Development"
      foldKey={`fold:${requirementId}:development`}
      summary={`${merged} of ${code.length} stories merged`}
      actions={<Badge tone={status.tone}>{status.label}</Badge>}
    >
      <p className="small">
        Garuda’s Developer agent codes each story in a sandbox and opens a pull request on {run.repository} into{' '}
        <code>{run.baseBranch ?? 'the default branch'}</code>{run.branchFrom ? ` (${run.branchFrom})` : ''}
        {controls && (run.branchOptions?.length ?? 0) > 0 && !changingBranch && (
          <> <button type="button" className="btn-ghost btn-sm" onClick={() => setChangingBranch(true)}>Change</button></>
        )}{' '}({run.agentSlots} at
        a time). Review and merge them in GitHub; the next wave starts once the current one is merged. Started{' '}
        {formatDateTime(run.startedAt)}{run.finishedAt && ` · finished ${formatDateTime(run.finishedAt)}`}.
      </p>
      {run.status === 'PAUSED' && <Callout tone="warning" title="Paused">No new story is started until development is resumed.</Callout>}
      {controls && (
        <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          {run.status === 'RUNNING'
            ? <button type="button" className="btn-secondary btn-sm" disabled={busy} onClick={() => void act('pause', 'Development paused.')}>Pause</button>
            : <button type="button" className="btn-primary btn-sm" disabled={busy} onClick={() => void act('resume', 'Development resumed.')}>Resume</button>}
          {changingBranch && (run.branchOptions?.length ?? 0) > 0 && (
            <label className="small">Works on branch
              <select value={run.baseBranch ?? ''} disabled={busy}
                onChange={(e) => { setChangingBranch(false); void act('base-branch', `Stories not yet coded (and retries) now use ${e.target.value}. `
                  + 'Pull requests already open keep their target; change it in GitHub if needed.', { branch: e.target.value }); }}>
                {!run.baseBranch && <option value="">default branch</option>}
                {run.branchOptions!.map((b) => <option key={`${b.environment ?? ""}:${b.branch}`} value={b.branch}>{branchLabel(b)}</option>)}
              </select>
            </label>
          )}
        </div>
      )}
      {outside.length > 0 && (
        <section aria-label="Outside work">
          <h3>Outside work <span className="small">close its GitHub issue or mark it done here when it is done</span></h3>
          <ul className="brd-items">{outside.map(renderTask)}</ul>
        </section>
      )}
      {waves.map((w) => {
        const tasks = code.filter((t) => t.wave === w);
        return (
          <WaveSection key={w} requirementId={requirementId} wave={w} tasks={tasks}
            current={w === run.currentWave && run.status !== 'DELIVERED'}>
            <ul className="brd-items">{tasks.map(renderTask)}</ul>
          </WaveSection>
        );
      })}
      <div style={{ marginTop: '1.25rem' }}>
        <ActivityTimeline requirementId={requirementId} stage="DEVELOPMENT" live={run.status === 'RUNNING'} milestonesOnly
          title="Milestones (each story's own log is under it)" />
      </div>
      {message && <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>}
    </FoldSection>
  );
}
