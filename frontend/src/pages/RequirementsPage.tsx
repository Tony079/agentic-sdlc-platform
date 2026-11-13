import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ProjectList, RequirementPage, RequirementState } from '../api/types';
import { Icon } from '../ui/Icon';
import { EmptyState, ErrorText, Loading, PageHeader, StatePill, relativeTime, stateLabel } from '../ui/components';

const FILTER_STATES: RequirementState[] = [
  'DRAFT', 'SUBMITTED', 'REPO_VALIDATING', 'MANUAL_INTAKE', 'AWAITING_DOCUMENTS', 'ANALYSING', 'INTERNAL_REVIEW',
  'SHARED_WITH_CLIENT', 'FAILED', 'REJECTED', 'CANCELLED',
];

/** Requirements of the user's projects (GD-05), filterable by state, project and ownership. */
export function RequirementsPage() {
  const [state, setState] = useState('');
  const [projectId, setProjectId] = useState('');
  const [mine, setMine] = useState(false);

  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const params = new URLSearchParams();
  if (state) params.set('state', state);
  if (projectId) params.set('projectId', projectId);
  if (mine) params.set('mine', 'true');
  const query = params.toString();
  const requirements = useQuery({
    queryKey: ['requirements', query],
    queryFn: () => api.get<RequirementPage>(`/api/v1/requirements${query ? `?${query}` : ''}`),
  });
  const projectName = (id: string) => projects.data?.items.find((p) => p.id === id)?.name ?? '';
  const canCreate = !!projects.data?.items.some((p) => p.myRole === 'REQUESTER' || p.myRole === 'APPROVER');
  const items = requirements.data?.items ?? [];

  return (
    <>
      <PageHeader
        title="Requirements"
        description="Business needs submitted in your projects, from draft to an approved business requirements document."
        actions={
          canCreate && (
            <Link to="/requirements/new" className="btn btn-primary">
              <Icon name="plus" size={16} />
              New requirement
            </Link>
          )
        }
      />
      <div className="card card-flush">
        <div className="card-header" style={{ flexWrap: 'wrap', justifyContent: 'flex-start' }}>
          <label style={{ margin: 0, minWidth: 200 }}>
            <span className="sr-only">State</span>
            <select value={state} onChange={(e) => setState(e.target.value)} aria-label="State">
              <option value="">All states</option>
              {FILTER_STATES.map((s) => (
                <option key={s} value={s}>
                  {stateLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label style={{ margin: 0, minWidth: 200 }}>
            <span className="sr-only">Project</span>
            <select value={projectId} onChange={(e) => setProjectId(e.target.value)} aria-label="Project">
              <option value="">All projects</option>
              {projects.data?.items.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className="check" style={{ margin: 0 }}>
            <input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} />
            Only mine
          </label>
          <span className="small" style={{ marginLeft: 'auto' }}>
            {requirements.data ? `${items.length} shown` : ''}
          </span>
        </div>
        {requirements.isPending && <div className="card-body"><Loading label="Loading requirements…" /></div>}
        {requirements.isError && <div className="card-body"><ErrorText>Requirements could not be loaded.</ErrorText></div>}
        {requirements.data && items.length === 0 && (
          <EmptyState icon="file" title={query ? 'No requirements match these filters' : 'No requirements yet'}>
            {query ? 'Try another state or project.' : 'Requirements created in your projects appear here.'}
          </EmptyState>
        )}
        {items.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">ID</th>
                  <th scope="col">Title</th>
                  <th scope="col">State</th>
                  <th scope="col">Project</th>
                  <th scope="col">Updated</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <tr key={r.id}>
                    <td className="mono small">
                      <Link to={`/requirements/${r.id}`}>{r.key}</Link>
                    </td>
                    <td>
                      <Link to={`/requirements/${r.id}`} className="primary-cell" style={{ color: 'var(--text)' }}>
                        {r.title}
                      </Link>
                    </td>
                    <td>
                      <StatePill state={r.state} />
                    </td>
                    <td>{projectName(r.projectId)}</td>
                    <td className="small" title={new Date(r.updatedAt).toLocaleString()}>
                      {relativeTime(r.updatedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
