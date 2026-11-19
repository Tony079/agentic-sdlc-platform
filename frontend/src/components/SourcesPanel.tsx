import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useToast } from '../ui/Toast';
import { Badge, Callout, Card, formatDateTime } from '../ui/components';
import type { EnvironmentOverview } from './EnvironmentsTab';

interface Source {
  repositoryId: string;
  repository: string;
  branch: string;
  analysedCommit: string;
  currentCommit: string | null;
  moved: boolean;
}

interface Sources {
  environmentId: string | null;
  environmentName: string | null;
  production: boolean;
  state: string;
  locked: boolean;
  canChangeEnvironment: boolean;
  analysedAt: string | null;
  analysed: Source[];
  preparing: { repository: string; branch: string; status: 'READY' | 'PREPARING' | 'FAILED'; failureCode: string | null }[];
  branchesMoved: boolean;
  newRepositories: boolean;
  canReanalyse: boolean;
  reanalyseHint: string | null;
}

const short = (sha: string | null) => (sha ? sha.slice(0, 12) : '—');

/** GD-23: the environment a requirement is analysed against, what was analysed, moved branches and re-analysis. */
export function SourcesPanel({ requirementId, projectId }: { requirementId: string; projectId: string }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const sources = useQuery({
    queryKey: ['sources', requirementId],
    queryFn: () => api.get<Sources>(`/api/v1/requirements/${requirementId}/sources`),
    refetchInterval: (q) => (q.state.data?.preparing.length ? 5000 : false),
  });
  const envs = useQuery({
    queryKey: ['environments', projectId],
    queryFn: () => api.get<EnvironmentOverview>(`/api/v1/projects/${projectId}/environments`),
  });
  const s = sources.data;
  if (!s) return null;
  const environments = envs.data?.environments ?? [];
  const current = environments.find((e) => e.id === s.environmentId);

  async function choose(environmentId: string) {
    try {
      await api.put(`/api/v1/requirements/${requirementId}/target-environment`, { environmentId: environmentId || null });
      toast.success('Environment saved.');
      await queryClient.invalidateQueries({ queryKey: ['sources', requirementId] });
      await queryClient.invalidateQueries({ queryKey: ['target-environment', requirementId] });
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'The environment could not be saved.');
    }
  }
  async function reanalyse() {
    if (!window.confirm('Re-analyse this requirement against the latest code? A new BRD version will be produced for review.')) {
      return;
    }
    try {
      await api.post(`/api/v1/requirements/${requirementId}/reanalyse`);
      toast.success('Re-analysis started. Garuda is fetching the latest code.');
      await queryClient.invalidateQueries({ queryKey: ['sources', requirementId] });
      await queryClient.invalidateQueries({ queryKey: ['requirement', requirementId] });
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Re-analysis could not be started.');
    }
  }

  return (
    <Card
      title="Code analysed"
      foldKey={`fold:${requirementId}:sources`}
      actions={s.environmentName ? (
        <span className="row">
          <span className="small">Environment</span>
          <Badge tone={s.production ? 'danger' : 'neutral'}>{s.environmentName}</Badge>
          {s.production && <Badge tone="danger">Production</Badge>}
        </span>
      ) : undefined}
    >
      {s.canChangeEnvironment && environments.length > 0 && (
        <label>
          Environment
          <select aria-label="Environment to analyse" value={s.environmentId ?? ''} onChange={(e) => void choose(e.target.value)}>
            <option value="">Choose…</option>
            {environments.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
                {e.production ? ' (PROD)' : ''}
              </option>
            ))}
          </select>
          <span className="hint">You can change it until the BRD quote is accepted.</span>
        </label>
      )}
      {s.analysed.length === 0 && current && (
        <p className="small">
          {current.branches.length === 0
            ? 'This environment has no branches yet.'
            : `Will analyse ${current.branches.map((b) => `${b.repository} @ ${b.branch}`).join(', ')}.`}
        </p>
      )}
      {s.analysed.length > 0 && (
        <>
          <p className="small">Analysed {formatDateTime(s.analysedAt)} at these commits:</p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Repository</th>
                  <th scope="col">Branch</th>
                  <th scope="col">Analysed</th>
                  <th scope="col">Now</th>
                </tr>
              </thead>
              <tbody>
                {s.analysed.map((a) => (
                  <tr key={a.repositoryId}>
                    <td>{a.repository}</td>
                    <td className="mono">{a.branch}</td>
                    <td className="mono">{short(a.analysedCommit)}</td>
                    <td>
                      {a.moved ? (
                        <Badge tone="warning">{a.currentCommit ? `Moved to ${short(a.currentCommit)}` : 'Branch changed'}</Badge>
                      ) : (
                        <span className="small">Unchanged</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {(s.branchesMoved || s.newRepositories) && (
        <Callout tone="warning" title="The code has changed since this analysis">
          {s.newRepositories && 'Repositories were added to the environment. '}
          The BRD still describes the commits analysed above; nothing changes until you re-analyse.
          {s.canReanalyse ? (
            <div style={{ marginTop: '0.5rem' }}>
              <button type="button" className="btn-primary btn-sm" onClick={() => void reanalyse()}>
                Re-analyse with the latest code
              </button>
            </div>
          ) : (
            s.reanalyseHint && <span className="small" style={{ display: 'block', marginTop: '0.25rem' }}>{s.reanalyseHint}</span>
          )}
        </Callout>
      )}
      {s.preparing.length > 0 && (
        <Callout tone="info" title="Preparing the latest code">
          {s.preparing.map((p) => (
            <span key={p.repository} style={{ display: 'block' }}>
              {p.repository} @ {p.branch}: {p.status === 'READY' ? 'ready' : p.status === 'FAILED' ? `failed (${p.failureCode ?? 'error'})` : 'fetching…'}
            </span>
          ))}
        </Callout>
      )}
    </Card>
  );
}
