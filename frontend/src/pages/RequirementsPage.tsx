import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ProjectList, RequirementPage, RequirementState } from '../api/types';

const FILTER_STATES: RequirementState[] = ['DRAFT', 'SUBMITTED', 'CANCELLED'];

export function RequirementsPage() {
  const [state, setState] = useState('');
  const [projectId, setProjectId] = useState('');

  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const params = new URLSearchParams();
  if (state) params.set('state', state);
  if (projectId) params.set('projectId', projectId);
  const query = params.toString();
  const requirements = useQuery({
    queryKey: ['requirements', query],
    queryFn: () => api.get<RequirementPage>(`/api/v1/requirements${query ? `?${query}` : ''}`),
  });
  const projectName = (id: string) => projects.data?.items.find((p) => p.id === id)?.name ?? '';

  return (
    <section>
      <h1>Requirements</h1>
      <p>
        <Link to="/requirements/new">New requirement</Link>
      </p>
      <div className="inline-form">
        <label>
          State
          <select value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">All</option>
            {FILTER_STATES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label>
          Project
          <select value={projectId} onChange={(e) => setProjectId(e.target.value)}>
            <option value="">All</option>
            {projects.data?.items.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {requirements.isPending && <p>Loading requirements…</p>}
      {requirements.isError && (
        <p role="alert" className="error">
          Requirements could not be loaded.
        </p>
      )}
      {requirements.data && requirements.data.items.length === 0 && <p>No requirements yet.</p>}
      {requirements.data && requirements.data.items.length > 0 && (
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
            {requirements.data.items.map((r) => (
              <tr key={r.id}>
                <td>
                  <Link to={`/requirements/${r.id}`}>{r.key}</Link>
                </td>
                <td>{r.title}</td>
                <td>
                  <span className={`state state-${r.state.toLowerCase()}`}>{r.state}</span>
                </td>
                <td>{projectName(r.projectId)}</td>
                <td>{new Date(r.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
