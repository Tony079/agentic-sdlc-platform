import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { useToast } from '../ui/Toast';
import { Badge, Card } from '../ui/components';
import type { EnvironmentOverview } from './EnvironmentsTab';

interface Target {
  environmentId: string | null;
  canChange: boolean;
  projectId: string;
}

/** GD-20: the environment (and so the branches) a requirement's analysis targets. */
export function TargetEnvironmentPanel({ requirementId, projectId }: { requirementId: string; projectId: string }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const target = useQuery({
    queryKey: ['target-environment', requirementId],
    queryFn: () => api.get<Target>(`/api/v1/requirements/${requirementId}/target-environment`),
  });
  const envs = useQuery({
    queryKey: ['environments', projectId],
    queryFn: () => api.get<EnvironmentOverview>(`/api/v1/projects/${projectId}/environments`),
  });
  if (!target.data || !envs.data) return null;
  const environments = envs.data.environments;
  const current = environments.find((e) => e.id === target.data.environmentId);
  async function choose(id: string) {
    try {
      await api.put(`/api/v1/requirements/${requirementId}/target-environment`, { environmentId: id || null });
      await queryClient.invalidateQueries({ queryKey: ['target-environment', requirementId] });
      toast.success('Target environment saved.');
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'The environment could not be saved.');
    }
  }
  return (
    <Card title="Target environment">
      {environments.length === 0 ? (
        <p className="small">This project has no environments yet. The Environment Manager adds them on the project's Environments tab.</p>
      ) : target.data.canChange ? (
        <label>
          Environment
          <select aria-label="Target environment" value={target.data.environmentId ?? ''} onChange={(e) => void choose(e.target.value)}>
            <option value="">Not chosen</option>
            {environments.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
                {e.production ? ' (PROD)' : ''}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <p>{current ? current.name : 'Not chosen'}</p>
      )}
      {current && (
        <div className="stack" style={{ marginTop: '0.75rem' }}>
          {current.production && <Badge tone="danger">PROD</Badge>}
          {current.branches.map((b) => (
            <span key={b.id} className="small">
              {b.repository} · <span className="mono">{b.branch}</span>
            </span>
          ))}
        </div>
      )}
    </Card>
  );
}
