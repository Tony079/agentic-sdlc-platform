import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Icon, type IconName } from '../ui/Icon';
import { formatDateTime } from '../ui/components';

export interface ActivityEntry {
  id: number;
  stage: 'BRD' | 'DESIGN' | 'ESTIMATE' | 'PLAN' | 'DEVELOPMENT';
  runId: string | null;
  kind: 'STEP' | 'DONE' | 'WARN' | 'FAIL' | 'DRAFT';
  message: string;
  createdAt: string;
  /** GD-36: the development story the line is about (its own log shows it). */
  taskId?: string | null;
}

const ICON: Record<ActivityEntry['kind'], IconName> = {
  STEP: 'activity',
  DONE: 'checkCircle',
  WARN: 'alert',
  FAIL: 'x',
  DRAFT: 'file',
};

/** "12s", "3m 05s": time since the run started, so the reader sees progress. */
function elapsed(from: string, to: string): string {
  const s = Math.max(0, Math.round((new Date(to).getTime() - new Date(from).getTime()) / 1000));
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`;
}

/** Groups lines by run (one analysis or design attempt), oldest run first. */
function runs(lines: ActivityEntry[]): ActivityEntry[][] {
  const groups = new Map<string, ActivityEntry[]>();
  for (const line of lines) {
    const key = line.runId ?? 'none';
    const group = groups.get(key);
    if (group) group.push(line);
    else groups.set(key, [line]);
  }
  return [...groups.values()];
}

/** Consecutive draft lines form one block under the step that wrote them. */
type Block = { kind: 'line'; line: ActivityEntry } | { kind: 'drafts'; lines: ActivityEntry[] };

function blocks(lines: ActivityEntry[]): Block[] {
  const out: Block[] = [];
  for (const line of lines) {
    const last = out[out.length - 1];
    if (line.kind === 'DRAFT' && last?.kind === 'drafts') last.lines.push(line);
    else if (line.kind === 'DRAFT') out.push({ kind: 'drafts', lines: [line] });
    else out.push({ kind: 'line', line });
  }
  return out;
}

export function ActivityLines({ lines, live }: { lines: ActivityEntry[]; live: boolean }) {
  return <Run lines={lines} live={live} />;
}

function Run({ lines, live }: { lines: ActivityEntry[]; live: boolean }) {
  const start = lines[0]?.createdAt ?? new Date().toISOString();
  const all = blocks(lines);
  const lastStep = [...lines].reverse().find((l) => l.kind !== 'DRAFT');
  return (
    <ol className="activity" aria-live={live ? 'polite' : undefined}>
      {all.map((block, i) => {
        if (block.kind === 'drafts') {
          const writing = live && i === all.length - 1;
          return (
            <li key={`d${block.lines[0]?.id}`} className="activity-drafts">
              <span className="activity-icon" aria-hidden="true"><Icon name="file" size={15} /></span>
              <details open={writing}>
                <summary className="small">
                  {writing ? 'Drafted so far' : 'Drafted while writing'}: {block.lines.length}{' '}
                  {block.lines.length === 1 ? 'item' : 'items'} (not checked yet; the final version is below)
                </summary>
                <ul>
                  {block.lines.map((d) => <li key={d.id}>{d.message}</li>)}
                </ul>
              </details>
              <span />
            </li>
          );
        }
        const line = block.line;
        const working = live && line.id === lastStep?.id && line.kind === 'STEP';
        return (
          <li key={line.id} className={`activity-${line.kind.toLowerCase()}`}>
            <span className="activity-icon" aria-hidden="true">
              {working ? <span className="spinner" /> : <Icon name={ICON[line.kind]} size={15} />}
            </span>
            <span className="activity-text">{line.message}</span>
            <span className="activity-time" title={formatDateTime(line.createdAt)}>+{elapsed(start, line.createdAt)}</span>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * GD-29: what the BA or Architect agent is doing, step by step, written by Garuda as it happens (never the model's
 * text). Live while the agent works; afterwards it folds into "How this was produced".
 */
export function ActivityTimeline({ requirementId, stage, live, title, expanded = false, milestonesOnly = false }: {
  requirementId: string;
  stage: ActivityEntry['stage'];
  live: boolean;
  title: string;
  /** Shown open without polling (for example after a failure). */
  expanded?: boolean;
  /** GD-36: leave out a story's step-by-step lines (they show under the story); keep its outcomes. */
  milestonesOnly?: boolean;
}) {
  // After a minute with no line at all, say so: the job is queued but no worker has picked it up.
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (!live) return undefined;
    const timer = window.setTimeout(() => setSlow(true), 60_000);
    return () => window.clearTimeout(timer);
  }, [live]);
  const activity = useQuery({
    queryKey: ['activity', requirementId],
    queryFn: () => api.get<ActivityEntry[]>(`/api/v1/requirements/${requirementId}/activity`),
    refetchInterval: live ? 2000 : false,
  });
  const lines = Array.isArray(activity.data) ? activity.data : [];
  const all = runs(lines.filter((l) => l.stage === stage && !(milestonesOnly && l.taskId && l.kind === 'STEP')));
  const latest = all[all.length - 1];
  if (!latest) {
    return live ? (
      <div className="small">
        <p className="row" style={{ gap: '0.5rem' }}>
          <span className="spinner" aria-hidden="true" /> Waiting for the agent to start…
        </p>
        {slow && (
          <p>
            Nothing has started after a minute. The background worker may be busy or stopped; an administrator can
            check that the worker service is running.
          </p>
        )}
      </div>
    ) : null;
  }
  const earlier = all.slice(0, -1);
  const body = (
    <>
      <Run lines={latest} live={live} />
      {earlier.length > 0 && (
        <details className="activity-earlier">
          <summary className="small">Earlier attempts ({earlier.length})</summary>
          {earlier.map((r) => <Run key={r[0]?.id} lines={r} live={false} />)}
        </details>
      )}
    </>
  );
  if (live || expanded) {
    return (
      <div className="activity-box" role="status">
        <div className="label">{title}</div>
        {body}
      </div>
    );
  }
  return (
    <details className="activity-box">
      <summary><strong>{title}</strong></summary>
      {body}
    </details>
  );
}
