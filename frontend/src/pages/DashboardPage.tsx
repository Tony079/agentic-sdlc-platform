import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Clarification, ProjectList, RequirementPage, RequirementState } from '../api/types';
import { useAuth } from '../auth/AuthContext';

const GROUPS: { label: string; states: RequirementState[] }[] = [
  { label: 'Drafts', states: ['DRAFT'] },
  {
    label: 'In progress',
    states: [
      'SUBMITTED', 'MANUAL_INTAKE', 'REPO_VALIDATING', 'AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION', 'ANALYSING',
      'BRD_DRAFTED', 'INTERNAL_REVIEW', 'REVISION_REQUESTED', 'INTERNAL_APPROVED', 'CLIENT_CHANGES_REQUESTED',
    ],
  },
  { label: 'Waiting for your answer', states: ['AWAITING_CLARIFICATION'] },
  { label: 'BRD shared with you', states: ['SHARED_WITH_CLIENT'] },
  { label: 'Ended', states: ['FAILED', 'REJECTED', 'CANCELLED'] },
];

const OPEN_CLARIFICATION = ['OPEN', 'NEEDS_INFO'];

/** Start page: what exists, what is waiting for the user, and where to go next. All figures come from the API. */
export function DashboardPage() {
  const { me } = useAuth();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const requirements = useQuery({
    queryKey: ['requirements', ''],
    queryFn: () => api.get<RequirementPage>('/api/v1/requirements'),
  });
  const clarifications = useQuery({
    queryKey: ['clarifications'],
    queryFn: () => api.get<Clarification[]>('/api/v1/clarifications'),
  });

  const items = requirements.data?.items ?? [];
  const openClarifications = (clarifications.data ?? []).filter((c) => OPEN_CLARIFICATION.includes(c.status));
  const loading = projects.isPending || requirements.isPending || clarifications.isPending;
  const failed = projects.isError || requirements.isError || clarifications.isError;

  return (
    <section>
      <h1>Dashboard</h1>
      {me && (
        <p>
          Signed in as {me.displayName} · {me.tenant.name}
        </p>
      )}
      <p>
        <Link to="/requirements/new">New requirement</Link> · <Link to="/projects">Projects</Link> ·{' '}
        <Link to="/requirements">All requirements</Link>
      </p>

      {loading && <p>Loading your dashboard…</p>}
      {failed && (
        <p role="alert" className="error">
          Some dashboard data could not be loaded. Reload the page to try again.
        </p>
      )}

      {!loading && !failed && (
        <>
          {openClarifications.length > 0 && (
            <p role="status" className="attention">
              {openClarifications.length === 1
                ? '1 clarification is waiting for your answer. '
                : `${openClarifications.length} clarifications are waiting for your answer. `}
              <Link to="/clarifications">Answer now</Link>
            </p>
          )}

          <h2>Your requirements</h2>
          {items.length === 0 ? (
            <p>
              You have no requirements yet.{' '}
              {projects.data && projects.data.items.length === 0 ? (
                <>
                  Start by <Link to="/projects">creating a project</Link>.
                </>
              ) : (
                <>
                  <Link to="/requirements/new">Create your first requirement</Link>.
                </>
              )}
            </p>
          ) : (
            <>
              <ul className="tiles">
                {GROUPS.map((group) => (
                  <li key={group.label}>
                    <span className="tile-count">{items.filter((r) => group.states.includes(r.state)).length}</span>
                    <span>{group.label}</span>
                  </li>
                ))}
                <li>
                  <span className="tile-count">{projects.data?.items.length ?? 0}</span>
                  <span>Projects</span>
                </li>
              </ul>
              {requirements.data?.nextCursor && (
                <p className="small">Counts cover your {items.length} most recently updated requirements.</p>
              )}

              <h2>Recently updated</h2>
              <table>
                <thead>
                  <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Title</th>
                    <th scope="col">State</th>
                    <th scope="col">Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {items.slice(0, 8).map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link to={`/requirements/${r.id}`}>{r.key}</Link>
                      </td>
                      <td>{r.title}</td>
                      <td>{r.state}</td>
                      <td>{new Date(r.updatedAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </>
      )}
    </section>
  );
}
