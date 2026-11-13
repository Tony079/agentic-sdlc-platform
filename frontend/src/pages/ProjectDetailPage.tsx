import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { OrgUser, Project, ProjectMember, ProjectRole, RequirementPage } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../ui/Icon';
import { useToast } from '../ui/Toast';
import {
  Avatar,
  BackLink,
  Badge,
  Callout,
  Card,
  EmptyState,
  ErrorText,
  Loading,
  Modal,
  PageHeader,
  ROLE_LABELS,
  RoleBadge,
  StatePill,
  Tabs,
  formatDate,
  relativeTime,
} from '../ui/components';
import { ProjectFormModal } from './ProjectsPage';

const ROLE_HELP: Record<ProjectRole, string> = {
  REQUESTER: 'Creates requirements, uploads documents and answers clarifications.',
  APPROVER: 'Everything a Requester does, plus approves BRDs, change decisions and repository links.',
  VIEWER: 'Reads requirements and BRDs of the project; cannot change anything.',
};

export const PROJECT_ROLES: ProjectRole[] = ['REQUESTER', 'APPROVER', 'VIEWER'];

function AddMemberModal({ project, existing, onClose }: { project: Project; existing: Set<string>; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const users = useQuery({ queryKey: ['org-users'], queryFn: () => api.get<OrgUser[]>('/api/v1/organisation/users') });
  const [userId, setUserId] = useState('');
  const [role, setRole] = useState<ProjectRole>('REQUESTER');
  const candidates = (users.data ?? []).filter((u) => u.status !== 'DISABLED' && !existing.has(u.id));
  const add = useMutation({
    mutationFn: () => api.post(`/api/v1/projects/${project.id}/members`, { userId, role }),
    onSuccess: async () => {
      toast.success('Member added.');
      await queryClient.invalidateQueries({ queryKey: ['project-members', project.id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      onClose();
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    add.mutate();
  }
  return (
    <Modal
      title="Add a member"
      description={`Give someone in your organisation access to ${project.name}.`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="add-member" disabled={!userId || add.isPending}>
            Add member
          </button>
        </>
      }
    >
      <form id="add-member" onSubmit={submit}>
        {users.isPending && <Loading />}
        {users.data && candidates.length === 0 && (
          <Callout>
            Everyone in the organisation is already a member. Invite new people from{' '}
            <Link to="/admin/users">Users</Link>.
          </Callout>
        )}
        {candidates.length > 0 && (
          <>
            <label>
              Person
              <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
                <option value="">Choose a person…</option>
                {candidates.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.displayName} ({u.email}){u.status === 'INVITED' ? ' – invited' : ''}
                  </option>
                ))}
              </select>
            </label>
            <fieldset className="field">
              <legend>Project role</legend>
              {PROJECT_ROLES.map((r) => (
                <label key={r} className="check" style={{ alignItems: 'flex-start' }}>
                  <input type="radio" name="role" checked={role === r} onChange={() => setRole(r)} />
                  <span>
                    <strong>{ROLE_LABELS[r]}</strong>
                    <span className="hint" style={{ display: 'block' }}>
                      {ROLE_HELP[r]}
                    </span>
                  </span>
                </label>
              ))}
            </fieldset>
          </>
        )}
        {add.isError && <ErrorText>The member could not be added.</ErrorText>}
      </form>
    </Modal>
  );
}

function MembersTab({ project, admin }: { project: Project; admin: boolean }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const members = useQuery({
    queryKey: ['project-members', project.id],
    queryFn: () => api.get<ProjectMember[]>(`/api/v1/projects/${project.id}/members`),
  });
  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['project-members', project.id] });
    await queryClient.invalidateQueries({ queryKey: ['projects'] });
  }
  async function changeRole(member: ProjectMember, role: ProjectRole) {
    try {
      await api.put(`/api/v1/project-members/${member.id}`, { role });
      toast.success(`${member.displayName} is now ${ROLE_LABELS[role]}.`);
      await refresh();
    } catch {
      toast.error('The role could not be changed.');
    }
  }
  async function remove(member: ProjectMember) {
    if (!window.confirm(`Remove ${member.displayName} from ${project.name}? They lose access to its requirements.`)) return;
    try {
      await api.post(`/api/v1/project-members/${member.id}/remove`);
      toast.success(`${member.displayName} was removed.`);
      await refresh();
    } catch {
      toast.error('The member could not be removed.');
    }
  }
  const list = members.data ?? [];
  return (
    <Card
      title={`Members (${list.length})`}
      flush
      actions={
        admin &&
        project.status !== 'ARCHIVED' && (
          <button type="button" className="btn-primary btn-sm" onClick={() => setAdding(true)}>
            <Icon name="userPlus" size={15} />
            Add member
          </button>
        )
      }
    >
      {members.isPending && <div className="card-body"><Loading /></div>}
      {members.isError && <div className="card-body"><ErrorText>Members could not be loaded.</ErrorText></div>}
      {members.data && list.length === 0 && <EmptyState icon="users" title="No members yet" />}
      {list.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Role</th>
                <th scope="col">Added</th>
                {admin && <th scope="col" className="num">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {list.map((m) => (
                <tr key={m.id}>
                  <td>
                    <div className="row">
                      <Avatar name={m.displayName} size="sm" />
                      <div>
                        <span className="primary-cell">{m.displayName}</span>
                        <span className="sub">{m.email}</span>
                      </div>
                      {m.userStatus === 'INVITED' && <Badge tone="warning">Invited</Badge>}
                      {m.userStatus === 'DISABLED' && <Badge tone="neutral">Deactivated</Badge>}
                    </div>
                  </td>
                  <td>
                    {admin ? (
                      <select
                        aria-label={`Role of ${m.displayName}`}
                        value={m.role}
                        onChange={(e) => void changeRole(m, e.target.value as ProjectRole)}
                        style={{ width: 'auto', minWidth: 140 }}
                      >
                        {PROJECT_ROLES.map((r) => (
                          <option key={r} value={r}>
                            {ROLE_LABELS[r]}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <RoleBadge role={m.role} />
                    )}
                  </td>
                  <td className="small">{formatDate(m.addedAt)}</td>
                  {admin && (
                    <td className="num">
                      <button type="button" className="btn-ghost btn-sm" onClick={() => void remove(m)}>
                        Remove
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {adding && <AddMemberModal project={project} existing={new Set(list.map((m) => m.userId))} onClose={() => setAdding(false)} />}
    </Card>
  );
}

function RequirementsTab({ project }: { project: Project }) {
  const requirements = useQuery({
    queryKey: ['requirements', `projectId=${project.id}`],
    queryFn: () => api.get<RequirementPage>(`/api/v1/requirements?projectId=${project.id}`),
  });
  const items = requirements.data?.items ?? [];
  const canCreate = project.status !== 'ARCHIVED' && (project.myRole === 'REQUESTER' || project.myRole === 'APPROVER');
  return (
    <Card
      title="Requirements"
      flush
      actions={
        canCreate && (
          <Link to={`/requirements/new?projectId=${project.id}`} className="btn btn-primary btn-sm">
            <Icon name="plus" size={15} />
            New requirement
          </Link>
        )
      }
    >
      {requirements.isPending && <div className="card-body"><Loading /></div>}
      {requirements.data && items.length === 0 && (
        <EmptyState icon="file" title="No requirements in this project">
          {project.myRole ? 'Requirements created in this project appear here.' : 'You are not a member of this project, so its requirements are not shown.'}
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
                  <td className="small">{relativeTime(r.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function SettingsTab({ project }: { project: Project }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  async function archive() {
    if (!window.confirm(`Archive ${project.name}? No new requirements can be created in it; existing ones stay readable.`)) return;
    try {
      await api.post(`/api/v1/projects/${project.id}/archive`);
      toast.success('Project archived.');
      await queryClient.invalidateQueries({ queryKey: ['project', project.id] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'The project could not be archived.');
    }
  }
  return (
    <div className="stack">
      <Card title="Details" actions={<button type="button" className="btn-secondary btn-sm" onClick={() => setEditing(true)}>Edit</button>}>
        <dl className="details">
          <dt>Name</dt>
          <dd>{project.name}</dd>
          <dt>Description</dt>
          <dd>{project.description || <span className="muted">None</span>}</dd>
          <dt>Created</dt>
          <dd>{formatDate(project.createdAt)}</dd>
          <dt>Status</dt>
          <dd>{project.status === 'ARCHIVED' ? `Archived ${formatDate(project.archivedAt)}` : 'Active'}</dd>
        </dl>
      </Card>
      {project.status !== 'ARCHIVED' && (
        <Card title="Archive project">
          <p className="small">
            Archiving stops new requirements in this project. Members keep read access to existing requirements, and nothing
            is deleted.
          </p>
          <button type="button" className="btn-danger" onClick={() => void archive()}>
            <Icon name="archive" size={16} />
            Archive project
          </button>
        </Card>
      )}
      {editing && <ProjectFormModal project={project} onClose={() => setEditing(false)} />}
    </div>
  );
}

export function ProjectDetailPage() {
  const { id = '' } = useParams();
  const { isOrgAdmin } = useAuth();
  const [tab, setTab] = useState<'requirements' | 'members' | 'settings'>('requirements');
  const project = useQuery({ queryKey: ['project', id], queryFn: () => api.get<Project>(`/api/v1/projects/${id}`) });

  if (project.isPending) return <Loading />;
  if (project.isError || !project.data) {
    return (
      <>
        <BackLink to="/projects">Projects</BackLink>
        <ErrorText>This project does not exist or you do not have access to it.</ErrorText>
      </>
    );
  }
  const p = project.data;
  const tabs: { id: typeof tab; label: string; count?: number }[] = [
    { id: 'requirements', label: 'Requirements' },
    { id: 'members', label: 'Members', count: p.memberCount },
  ];
  if (isOrgAdmin) tabs.push({ id: 'settings', label: 'Settings' });

  return (
    <>
      <BackLink to="/projects">Projects</BackLink>
      <PageHeader
        title={
          <span className="row" style={{ gap: '0.75rem' }}>
            {p.name}
            {p.status === 'ARCHIVED' && <Badge tone="neutral">Archived</Badge>}
          </span>
        }
        description={p.description || undefined}
        actions={
          <span className="row">
            <span className="small">Your role</span>
            <RoleBadge role={p.myRole} />
          </span>
        }
      />
      {isOrgAdmin && !p.myRole && (
        <Callout>
          You manage this project as Organisation Admin. To see its requirements, add yourself as a member (this is recorded
          in the audit history).
        </Callout>
      )}
      <Tabs label="Project sections" tabs={tabs} value={tab} onChange={setTab} />
      {tab === 'requirements' && <RequirementsTab project={p} />}
      {tab === 'members' && <MembersTab project={p} admin={isOrgAdmin} />}
      {tab === 'settings' && isOrgAdmin && <SettingsTab project={p} />}
    </>
  );
}
