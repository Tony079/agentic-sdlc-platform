import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Clarification, ProjectList, RequirementPage } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../ui/Icon';
import { Card, EmptyState, Loading, PageHeader, RoleBadge, Stat, StatePill, relativeTime } from '../ui/components';

const WAITING_ON_CLIENT = new Set(['AWAITING_CLARIFICATION', 'AWAITING_DOCUMENTS', 'AWAITING_DOCUMENT_DECISION', 'DRAFT']);
const DONE = new Set(['SHARED_WITH_CLIENT', 'REJECTED', 'CANCELLED']);

function greeting(): string {
  const hour = new Date().getHours();
  return hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
}

/** Personal overview: what needs the user's attention, their recent requirements and projects. */
export function HomePage() {
  const { me, isOrgAdmin } = useAuth();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const mine = useQuery({
    queryKey: ['requirements', 'mine=true'],
    queryFn: () => api.get<RequirementPage>('/api/v1/requirements?mine=true'),
  });
  const visible = useQuery({ queryKey: ['requirements', ''], queryFn: () => api.get<RequirementPage>('/api/v1/requirements') });
  const clarifications = useQuery({
    queryKey: ['clarifications'],
    queryFn: () => api.get<Clarification[]>('/api/v1/clarifications'),
  });

  const myItems = mine.data?.items ?? [];
  const openClarifications = (clarifications.data ?? []).filter((c) => c.status === 'OPEN' || c.status === 'NEEDS_INFO');
  const inProgress = myItems.filter((r) => !DONE.has(r.state)).length;
  const waiting = myItems.filter((r) => WAITING_ON_CLIENT.has(r.state)).length;
  const shared = (visible.data?.items ?? []).filter((r) => r.state === 'SHARED_WITH_CLIENT').length;
  const projectName = (id: string) => projects.data?.items.find((p) => p.id === id)?.name ?? '';
  const canRequest = !!projects.data?.items.some((p) => p.myRole === 'REQUESTER' || p.myRole === 'APPROVER');

  return (
    <>
      <PageHeader
        eyebrow={me?.tenant.name}
        title={`${greeting()}, ${me?.displayName.split(' ')[0] ?? ''}`}
        description="Here is what is happening across your projects."
        actions={
          canRequest && (
            <Link to="/requirements/new" className="btn btn-primary">
              <Icon name="plus" size={16} />
              New requirement
            </Link>
          )
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat icon="file" label="My open requirements" value={mine.isPending ? '–' : inProgress} meta={`${myItems.length} in total`} />
        <Stat icon="clock" label="Waiting on you" value={mine.isPending ? '–' : waiting} meta="Drafts, documents or decisions" />
        <Stat icon="chat" label="Open clarifications" value={clarifications.isPending ? '–' : openClarifications.length} meta="Questions from the analysts" />
        <Stat icon="checkCircle" label="BRDs shared" value={visible.isPending ? '–' : shared} meta="Ready for your review" />
      </div>
      <div className="grid grid-main-side">
        <Card
          title="My recent requirements"
          flush
          actions={
            <Link to="/requirements" className="btn btn-ghost btn-sm">
              View all
              <Icon name="arrowRight" size={15} />
            </Link>
          }
        >
          {mine.isPending && <div className="card-body"><Loading /></div>}
          {mine.data && myItems.length === 0 && (
            <EmptyState icon="file" title="No requirements yet">
              Describe a business need and Garuda&apos;s analysts turn it into a business requirements document.
            </EmptyState>
          )}
          {myItems.length > 0 && (
            <ul className="list">
              {myItems.slice(0, 8).map((r) => (
                <li key={r.id}>
                  <span className="mono small">{r.key}</span>
                  <div className="grow">
                    <Link to={`/requirements/${r.id}`} className="title" style={{ display: 'block', color: 'var(--text)' }}>
                      {r.title}
                    </Link>
                    <span className="small">
                      {projectName(r.projectId)} · updated {relativeTime(r.updatedAt)}
                    </span>
                  </div>
                  <StatePill state={r.state} />
                </li>
              ))}
            </ul>
          )}
        </Card>
        <div className="stack">
          <Card
            title="My projects"
            flush
            actions={
              <Link to="/projects" className="btn btn-ghost btn-sm">
                All projects
              </Link>
            }
          >
            {projects.isPending && <div className="card-body"><Loading /></div>}
            {projects.data && projects.data.items.length === 0 && (
              <EmptyState icon="folder" title="No projects">
                {isOrgAdmin ? 'Create your first project to start.' : 'Ask your Organisation Admin to add you to a project.'}
              </EmptyState>
            )}
            <ul className="list">
              {projects.data?.items.slice(0, 6).map((p) => (
                <li key={p.id}>
                  <span className="stat-icon">
                    <Icon name="folder" size={15} />
                  </span>
                  <div className="grow">
                    <Link to={`/projects/${p.id}`} className="title" style={{ display: 'block', color: 'var(--text)' }}>
                      {p.name}
                    </Link>
                  </div>
                  <RoleBadge role={p.myRole} />
                </li>
              ))}
            </ul>
          </Card>
          {openClarifications.length > 0 && (
            <Card title="Questions for you" flush>
              <ul className="list">
                {openClarifications.slice(0, 5).map((c) => (
                  <li key={c.id}>
                    <Icon name="chat" />
                    <div className="grow">
                      <Link to={`/requirements/${c.requirementId}`} className="title" style={{ display: 'block', color: 'var(--text)' }}>
                        {c.question}
                      </Link>
                      <span className="small">{relativeTime(c.createdAt)}</span>
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
