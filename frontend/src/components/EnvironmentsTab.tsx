import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Project } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../ui/Icon';
import { useToast } from '../ui/Toast';
import { ActionMenu, Badge, Callout, Card, EmptyState, ErrorText, Loading, Modal, formatDateTime, relativeTime, type Tone } from '../ui/components';

export interface EnvBranch {
  id: string;
  repositoryId: string;
  repository: string;
  branch: string;
  status: 'PENDING_VERIFICATION' | 'VERIFIED' | 'BRANCH_MISSING' | 'ACCESS_DENIED' | 'UNAVAILABLE';
  headSha: string | null;
  failureCode: string | null;
  verifiedAt: string | null;
}

export interface EnvironmentItem {
  id: string;
  name: string;
  description: string | null;
  production: boolean;
  branches: EnvBranch[];
}

interface EnvRepository {
  id: string;
  fullName: string;
  url: string;
  status: string;
  statusReason: string | null;
  defaultBranch: string | null;
}

interface EnvRequest {
  id: string;
  environmentId: string | null;
  environmentName: string | null;
  kind: string;
  repository: string | null;
  branch: string | null;
  newName: string | null;
  reason: string;
  status: string;
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
  approvedByName: string | null;
  executedAt: string | null;
}

export interface EnvironmentOverview {
  projectId: string;
  projectStatus: string;
  environmentManagerId: string | null;
  environmentManagerName: string | null;
  canManage: boolean;
  canApproveProduction: boolean;
  repositories: EnvRepository[];
  environments: EnvironmentItem[];
  requests: EnvRequest[];
}

const STATUS: Record<string, { label: string; tone: Tone }> = {
  PENDING_VERIFICATION: { label: 'Checking', tone: 'info' },
  VERIFIED: { label: 'Verified', tone: 'success' },
  BRANCH_MISSING: { label: 'Branch not found', tone: 'danger' },
  ACCESS_DENIED: { label: 'App not installed', tone: 'danger' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'danger' },
};

function describe(r: EnvRequest): string {
  switch (r.kind) {
    case 'MARK_PRODUCTION':
      return `Make ${r.environmentName} the PROD environment`;
    case 'ADD_BRANCH':
      return `PROD: add ${r.repository} → ${r.branch}`;
    case 'CHANGE_BRANCH':
      return `PROD: change ${r.repository} to ${r.branch}`;
    case 'REMOVE_BRANCH':
      return `PROD: remove ${r.repository}`;
    case 'RENAME_ENVIRONMENT':
      return `Rename PROD to ${r.newName}`;
    case 'REMOVE_ENVIRONMENT':
      return `Remove the PROD environment`;
    default:
      return r.kind;
  }
}

type Dialog =
  | { kind: 'connect' }
  | { kind: 'environment' }
  | { kind: 'branch'; env: EnvironmentItem }
  | { kind: 'changeBranch'; env: EnvironmentItem; branch: EnvBranch }
  | { kind: 'prodReason'; title: string; run: (reason: string) => Promise<unknown> };

/** GD-20/GD-21: environments, repositories and branches of one project. */
export function EnvironmentsTab({ project }: { project: Project }) {
  const { me } = useAuth();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const overview = useQuery({
    queryKey: ['environments', project.id],
    queryFn: () => api.get<EnvironmentOverview>(`/api/v1/projects/${project.id}/environments`),
    refetchInterval: (q) =>
      q.state.data?.environments.some((e) => e.branches.some((b) => b.status === 'PENDING_VERIFICATION')) ||
      q.state.data?.repositories.some((r) => r.status === 'PENDING_VERIFICATION')
        ? 4000
        : false,
  });
  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['environments', project.id] });
  }
  async function run(action: () => Promise<{ applied: boolean } | void>, applied: string) {
    try {
      const result = await action();
      toast.success(result && result.applied === false ? 'Sent to a BRD Approver for approval.' : applied);
      await refresh();
      return true;
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'The change failed.');
      return false;
    }
  }
  if (overview.isPending) return <Loading />;
  if (overview.isError || !overview.data) return <ErrorText>Environments could not be loaded.</ErrorText>;
  const o = overview.data;
  const pending = o.requests.filter((r) => r.status === 'REQUESTED');

  return (
    <div className="stack">
      {project.status !== 'ACTIVE' && <Callout tone="warning">Environments can be set up once Garuda has activated the project.</Callout>}
      {!o.environmentManagerId && project.status === 'ACTIVE' && (
        <Callout tone="warning">No Environment Manager yet. An Organisation Admin names one on the Members tab.</Callout>
      )}
      {o.environmentManagerName && (
        <p className="small">
          Environment Manager: <strong>{o.environmentManagerName}</strong>
          {o.canManage ? ' (you)' : ''}. Changes to the PROD environment need approval by a BRD Approver.
        </p>
      )}
      {pending.length > 0 && (
        <Card title={`PROD changes waiting for approval (${pending.length})`} flush>
          <ul className="list">
            {pending.map((r) => {
              const mine = r.requestedBy === me?.userId;
              return (
                <li key={r.id}>
                  <Icon name="shield" />
                  <div className="grow">
                    <div className="title">{describe(r)}</div>
                    <span className="small">
                      {r.reason} · by {r.requestedByName} {relativeTime(r.requestedAt)}
                    </span>
                  </div>
                  {mine && (
                    <button type="button" className="btn-ghost btn-sm" onClick={() => void run(() => api.post(`/api/v1/environment-requests/${r.id}/cancel`), 'Request cancelled.')}>
                      Cancel
                    </button>
                  )}
                  {o.canApproveProduction && !mine && (
                    <>
                      <button type="button" className="btn-secondary btn-sm" onClick={() => void run(() => api.post(`/api/v1/environment-requests/${r.id}/reject`), 'Request rejected.')}>
                        Reject
                      </button>
                      <button
                        type="button"
                        className="btn-primary btn-sm"
                        onClick={() => {
                          if (window.confirm(`Approve: ${describe(r)}?`)) void run(() => api.post(`/api/v1/environment-requests/${r.id}/approve`), 'Approved and applied.');
                        }}
                      >
                        Approve
                      </button>
                    </>
                  )}
                </li>
              );
            })}
          </ul>
        </Card>
      )}
      <Card
        title="Repositories"
        flush
        actions={
          o.canManage && (
            <button type="button" className="btn-secondary btn-sm" onClick={() => setDialog({ kind: 'connect' })}>
              <Icon name="git" size={15} />
              Connect repository
            </button>
          )
        }
      >
        {o.repositories.length === 0 ? (
          <EmptyState icon="git" title="No repositories connected">
            Install the Garuda GitHub App on your GitHub organisation, then connect the repositories of this product.
          </EmptyState>
        ) : (
          <ul className="list">
            {o.repositories.map((r) => (
              <li key={r.id}>
                <Icon name="git" />
                <div className="grow">
                  <a className="title" href={r.url} target="_blank" rel="noreferrer" style={{ display: 'block' }}>
                    {r.fullName}
                  </a>
                  <span className="small">{r.defaultBranch ? `Default branch ${r.defaultBranch}` : 'Waiting for GitHub'}</span>
                </div>
                <Badge tone={STATUS[r.status]?.tone ?? 'neutral'}>{STATUS[r.status]?.label ?? r.status}</Badge>
              </li>
            ))}
          </ul>
        )}
        {o.repositories.some((r) => r.status === 'ACCESS_DENIED') && (
          <div className="card-body">
            <Callout tone="warning">
              Garuda cannot read a repository marked “App not installed”. Ask your GitHub administrator to install the Garuda GitHub
              App on it (read-only access), then check again.
            </Callout>
          </div>
        )}
      </Card>
      <Card
        title="Environments"
        flush
        actions={
          o.canManage && (
            <>
              <button type="button" className="btn-ghost btn-sm" onClick={() => void run(() => api.post(`/api/v1/projects/${project.id}/environments/verify`), 'Checking again with GitHub.')}>
                <Icon name="refresh" size={15} />
                Check again
              </button>
              <button type="button" className="btn-primary btn-sm" onClick={() => setDialog({ kind: 'environment' })}>
                <Icon name="plus" size={15} />
                Add environment
              </button>
            </>
          )
        }
      >
        {o.environments.length === 0 ? (
          <EmptyState icon="layers" title="No environments yet">
            Add the environments of this product, for example DEV, QC, Pre-prod and PROD, and the branch each one uses.
          </EmptyState>
        ) : (
          o.environments.map((env) => (
            <div key={env.id} style={{ borderBottom: '1px solid var(--border)' }}>
              <div className="card-body spread" style={{ paddingBottom: '0.5rem' }}>
                <div className="row">
                  <strong>{env.name}</strong>
                  {env.production && <Badge tone="danger">PROD</Badge>}
                  {env.description && <span className="small">{env.description}</span>}
                </div>
                {o.canManage && (
                  <div className="row">
                    <button type="button" className="btn-secondary btn-sm" disabled={o.repositories.length === 0} onClick={() => setDialog({ kind: 'branch', env })}>
                      <Icon name="plus" size={15} />
                      Branch
                    </button>
                    <ActionMenu label={`Actions for ${env.name}`}>
                      {(close) => (
                        <>
                          {!env.production && (
                            <button
                              type="button"
                              role="menuitem"
                              onClick={() => {
                                close();
                                setDialog({
                                  kind: 'prodReason',
                                  title: `Make ${env.name} the PROD environment`,
                                  run: (reason) => api.post(`/api/v1/environments/${env.id}/mark-production`, { reason }),
                                });
                              }}
                            >
                              <Icon name="shield" size={16} />
                              Mark as PROD
                            </button>
                          )}
                          <button
                            type="button"
                            role="menuitem"
                            className="danger"
                            onClick={() => {
                              close();
                              if (env.production) {
                                setDialog({
                                  kind: 'prodReason',
                                  title: `Remove ${env.name}`,
                                  run: (reason) => api.delete(`/api/v1/environments/${env.id}?reason=${encodeURIComponent(reason)}`),
                                });
                              } else if (window.confirm(`Remove ${env.name} and its branches?`)) {
                                void run(() => api.delete(`/api/v1/environments/${env.id}`), 'Environment removed.');
                              }
                            }}
                          >
                            <Icon name="trash" size={16} />
                            Remove environment
                          </button>
                        </>
                      )}
                    </ActionMenu>
                  </div>
                )}
              </div>
              {env.branches.length === 0 ? (
                <p className="small" style={{ padding: '0 1.25rem 1rem' }}>
                  No branches yet.
                </p>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th scope="col">Repository</th>
                        <th scope="col">Branch</th>
                        <th scope="col">Status</th>
                        <th scope="col">Head commit</th>
                        {o.canManage && (
                          <th scope="col" className="num">
                            <span className="sr-only">Actions</span>
                          </th>
                        )}
                      </tr>
                    </thead>
                    <tbody>
                      {env.branches.map((b) => (
                        <tr key={b.id}>
                          <td>{b.repository}</td>
                          <td className="mono">{b.branch}</td>
                          <td>
                            <Badge tone={STATUS[b.status]?.tone ?? 'neutral'}>{STATUS[b.status]?.label ?? b.status}</Badge>
                          </td>
                          <td className="small">
                            {b.headSha ? (
                              <>
                                <span className="mono">{b.headSha.slice(0, 12)}</span> · {formatDateTime(b.verifiedAt)}
                              </>
                            ) : (
                              '—'
                            )}
                          </td>
                          {o.canManage && (
                            <td className="num">
                              <ActionMenu label={`Actions for ${b.repository}`}>
                                {(close) => (
                                  <>
                                    <button
                                      type="button"
                                      role="menuitem"
                                      onClick={() => {
                                        close();
                                        setDialog({ kind: 'changeBranch', env, branch: b });
                                      }}
                                    >
                                      Change branch
                                    </button>
                                    <button
                                      type="button"
                                      role="menuitem"
                                      className="danger"
                                      onClick={() => {
                                        close();
                                        if (env.production) {
                                          setDialog({
                                            kind: 'prodReason',
                                            title: `Remove ${b.repository} from ${env.name}`,
                                            run: (reason) => api.delete(`/api/v1/environment-branches/${b.id}?reason=${encodeURIComponent(reason)}`),
                                          });
                                        } else {
                                          void run(() => api.delete(`/api/v1/environment-branches/${b.id}`), 'Branch removed.');
                                        }
                                      }}
                                    >
                                      Remove
                                    </button>
                                  </>
                                )}
                              </ActionMenu>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ))
        )}
      </Card>
      {dialog && <EnvironmentDialog dialog={dialog} overview={o} projectId={project.id} onClose={() => setDialog(null)} run={run} />}
    </div>
  );
}

function EnvironmentDialog({
  dialog,
  overview,
  projectId,
  onClose,
  run,
}: {
  dialog: Dialog;
  overview: EnvironmentOverview;
  projectId: string;
  onClose: () => void;
  run: (action: () => Promise<{ applied: boolean } | void>, applied: string) => Promise<boolean>;
}) {
  const [value, setValue] = useState('');
  const [second, setSecond] = useState('');
  const [reason, setReason] = useState('');
  const prod = (dialog.kind === 'branch' || dialog.kind === 'changeBranch') && dialog.env.production;
  const needsReason = prod || dialog.kind === 'prodReason';
  const usedRepos = dialog.kind === 'branch' ? new Set(dialog.env.branches.map((b) => b.repositoryId)) : new Set<string>();
  const title =
    dialog.kind === 'connect'
      ? 'Connect a GitHub repository'
      : dialog.kind === 'environment'
        ? 'Add an environment'
        : dialog.kind === 'branch'
          ? `Add a branch to ${dialog.env.name}`
          : dialog.kind === 'changeBranch'
            ? `Change the branch of ${dialog.branch.repository} in ${dialog.env.name}`
            : dialog.title;
  async function submit(event: FormEvent) {
    event.preventDefault();
    let ok = false;
    switch (dialog.kind) {
      case 'connect':
        ok = await run(() => api.post(`/api/v1/projects/${projectId}/connected-repositories`, { repository: value.trim() }), 'Repository connected. Garuda is checking access.');
        break;
      case 'environment':
        ok = await run(() => api.post(`/api/v1/projects/${projectId}/environments`, { name: value.trim(), description: second.trim() || null }), 'Environment added.');
        break;
      case 'branch':
        ok = await run(() => api.post(`/api/v1/environments/${dialog.env.id}/branches`, { repositoryId: second, branch: value.trim(), reason: reason.trim() || null }), 'Branch added. Garuda is checking it.');
        break;
      case 'changeBranch':
        ok = await run(() => api.put(`/api/v1/environment-branches/${dialog.branch.id}`, { branch: value.trim(), reason: reason.trim() || null }), 'Branch changed.');
        break;
      case 'prodReason':
        ok = await run(() => dialog.run(reason.trim()) as Promise<{ applied: boolean }>, 'Done.');
        break;
    }
    if (ok) onClose();
  }
  const ready =
    (dialog.kind === 'prodReason' || value.trim()) && (dialog.kind !== 'branch' || second) && (!needsReason || reason.trim().length >= 5);
  return (
    <Modal
      title={title}
      description={needsReason ? 'This changes the PROD environment, so a BRD Approver of the project must approve it.' : undefined}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="env-dialog" disabled={!ready}>
            {needsReason ? 'Request approval' : 'Save'}
          </button>
        </>
      }
    >
      <form id="env-dialog" onSubmit={(e) => void submit(e)}>
        {dialog.kind === 'connect' && (
          <>
            <label>
              Repository
              <input value={value} onChange={(e) => setValue(e.target.value)} placeholder="acme/payments-api or https://github.com/acme/payments-api" />
              <span className="hint">The Garuda GitHub App must be installed on it. Garuda only reads code; it never writes.</span>
            </label>
          </>
        )}
        {dialog.kind === 'environment' && (
          <>
            <label>
              Name
              <input value={value} onChange={(e) => setValue(e.target.value)} maxLength={60} placeholder="QC" />
            </label>
            <label>
              Description
              <input value={second} onChange={(e) => setSecond(e.target.value)} maxLength={500} placeholder="Quality control, deployed nightly" />
            </label>
            <span className="hint">New environments start as non-production. Marking one as PROD needs a BRD Approver.</span>
          </>
        )}
        {dialog.kind === 'branch' && (
          <>
            <label>
              Repository
              <select value={second} onChange={(e) => setSecond(e.target.value)}>
                <option value="">Choose…</option>
                {overview.repositories
                  .filter((r) => !usedRepos.has(r.id))
                  .map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.fullName}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Branch
              <input value={value} onChange={(e) => setValue(e.target.value)} placeholder="release/qc" className="mono" />
            </label>
          </>
        )}
        {dialog.kind === 'changeBranch' && (
          <label>
            New branch
            <input value={value} onChange={(e) => setValue(e.target.value)} placeholder={dialog.branch.branch} className="mono" />
          </label>
        )}
        {needsReason && (
          <label>
            Reason for the approver
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={2000} />
          </label>
        )}
      </form>
    </Modal>
  );
}
