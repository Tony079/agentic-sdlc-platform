import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Badge, Callout, formatDateTime, type Tone } from '../ui/components';
import { ActivityTimeline } from './ActivityTimeline';
import { FoldSection } from '../ui/Fold';

// GD-36: the Development stage. Garuda's Developer agent codes each story of the pushed plan, wave by wave, and opens a
// pull request per story; people review and merge in GitHub.

type TaskStatus = 'WAITING' | 'QUEUED' | 'CODING' | 'PR_OPEN' | 'MERGED' | 'CLOSED' | 'FAILED' | 'SKIPPED' | 'DONE';
interface DevTask {
  id: string; storyKey: string; title: string; wave: number; external: boolean; issueNumber: number | null;
  status: TaskStatus; attempts: number; branch: string | null; prNumber: number | null; prUrl: string | null;
  summary: string | null; testsPassed: boolean | null; filesChanged: number | null; failureCode: string | null;
  reason: string | null; updatedAt: string;
}
interface DevRun {
  id: string; status: 'RUNNING' | 'PAUSED' | 'DELIVERED' | 'STOPPED'; repository: string; currentWave: number;
  agentSlots: number; startedAt: string; finishedAt: string | null; tasks: DevTask[]; counts: Record<string, number>;
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
  const [message, setMessage] = useState<{ tone: 'info' | 'danger'; text: string } | null>(null);

  async function act(path: string, done: string) {
    setBusy(true);
    setMessage(null);
    try {
      const next = await api.post<DevState>(`/api/v1/requirements/${requirementId}/development/${path}`, {});
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
      {t.prUrl && <> <a href={t.prUrl} target="_blank" rel="noopener noreferrer" className="small">pull request #{t.prNumber}</a></>}
      {!t.prUrl && t.issueNumber != null && <span className="small"> · issue #{t.issueNumber}</span>}
      {t.summary && <div className="small">{t.summary}</div>}
      {(t.testsPassed != null || t.filesChanged != null) && (
        <div className="small">
          {t.filesChanged != null && `${t.filesChanged} files changed`}
          {t.testsPassed != null && ` · tests ${t.testsPassed ? 'passing' : 'not passing'}`}
          {t.attempts > 1 && ` · attempt ${t.attempts}`}
        </div>
      )}
      {t.reason && <div className="small error">Not coded: {t.reason}.</div>}
      {controls && (t.status === 'FAILED' || t.status === 'WAITING' || t.status === 'QUEUED') && (
        <div className="row" style={{ marginTop: '0.3rem' }}>
          {t.status === 'FAILED' && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy}
              onClick={() => void act(`tasks/${t.id}/retry`, 'The Developer agent will try this story again.')}>Retry</button>
          )}
          {!t.external && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy}
              onClick={() => void act(`tasks/${t.id}/skip`, 'Story skipped.')}>Skip</button>
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
        Garuda’s Developer agent codes each story in a sandbox and opens a pull request on {run.repository} ({run.agentSlots} at
        a time). Review and merge them in GitHub; the next wave starts once the current one is merged. Started{' '}
        {formatDateTime(run.startedAt)}{run.finishedAt && ` · finished ${formatDateTime(run.finishedAt)}`}.
      </p>
      {run.status === 'PAUSED' && <Callout tone="warning" title="Paused">No new story is started until development is resumed.</Callout>}
      {controls && (
        <div className="row">
          {run.status === 'RUNNING'
            ? <button type="button" className="btn-secondary btn-sm" disabled={busy} onClick={() => void act('pause', 'Development paused.')}>Pause</button>
            : <button type="button" className="btn-primary btn-sm" disabled={busy} onClick={() => void act('resume', 'Development resumed.')}>Resume</button>}
        </div>
      )}
      {outside.length > 0 && (
        <section aria-label="Outside work">
          <h3>Outside work <span className="small">close each GitHub issue when it is done</span></h3>
          <ul className="brd-items">{outside.map(renderTask)}</ul>
        </section>
      )}
      {waves.map((w) => (
        <section key={w} aria-label={`Wave ${w}`}>
          <h3>Wave {w}{w === run.currentWave && run.status !== 'DELIVERED' && <> <Badge tone="info">current</Badge></>}</h3>
          <ul className="brd-items">{code.filter((t) => t.wave === w).map(renderTask)}</ul>
        </section>
      ))}
      <div style={{ marginTop: '1.25rem' }}>
        <ActivityTimeline requirementId={requirementId} stage="DEVELOPMENT" live={run.status === 'RUNNING'} title="What the Developer agent is doing" />
      </div>
      {message && <p className={message.tone === 'danger' ? 'error' : 'notice'} role="status">{message.text}</p>}
    </FoldSection>
  );
}
