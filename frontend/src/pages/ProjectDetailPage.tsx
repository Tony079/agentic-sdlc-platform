import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { OrgUser, Project, ProjectMember, ProjectRole, RequirementPage } from '../api/types';
import { EnvironmentsTab } from '../components/EnvironmentsTab';
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
  RoleBadges,
  StatePill,
  Tabs,
  canRequest,
  formatDate,
  relativeTime,
} from '../ui/components';
import { ProjectFormModal } from './ProjectsPage';
import { RolePicker } from '../components/RolePicker';

export { PROJECT_ROLES } from '../components/RolePicker';

function AddMemberModal({ project, existing, onClose }: { project: Project; existing: Set<string>; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const users = useQuery({ queryKey: ['org-users'], queryFn: () => api.get<OrgUser[]>('/api/v1/organisation/users') });
  const [userId, setUserId] = useState('');
  const [roles, setRoles] = useState<ProjectRole[]>(['REQUIREMENT_AUTHOR']);
  const candidates = (users.data ?? []).filter((u) => u.status !== 'DISABLED' && !existing.has(u.id));
  const add = useMutation({
    mutationFn: () => api.post(`/api/v1/projects/${project.id}/members`, { userId, roles }),
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
          <button type="submit" form="add-member" disabled={!userId || roles.length === 0 || add.isPending}>
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
            <RolePicker value={roles} onChange={setRoles} />
          </>
        )}
        {add.isError && <ErrorText>The member could not be added.</ErrorText>}
      </form>
    </Modal>
  );
}

/** GD-27: change a member's roles (one or more; Viewer alone). */
function EditRolesModal({ member, onClose, onSaved }: { member: ProjectMember; onClose: () => void; onSaved: () => Promise<void> }) {
  const toast = useToast();
  const [roles, setRoles] = useState<ProjectRole[]>(member.roles);
  const [busy, setBusy] = useState(false);
  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await api.put(`/api/v1/project-members/${member.id}`, { roles });
      toast.success(`${member.displayName}: ${roles.map((r) => ROLE_LABELS[r]).join(', ')}.`);
      await onSaved();
      onClose();
    } catch {
      toast.error('The roles could not be changed.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      title={`Roles of ${member.displayName}`}
      description="A member can hold several roles. Viewer is held on its own."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" form="edit-roles" disabled={busy || roles.length === 0}>Save roles</button>
        </>
      }
    >
      <form id="edit-roles" onSubmit={(e) => void save(e)}>
        <RolePicker value={roles} onChange={setRoles} />
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
  async function setManager(userId: string) {
    try {
      await api.put(`/api/v1/projects/${project.id}/environment-manager`, { userId: userId || null });
      toast.success(userId ? 'Environment Manager updated.' : 'Environment Manager removed.');
      await queryClient.invalidateQueries({ queryKey: ['project', project.id] });
      await refresh();
    } catch {
      toast.error('The Environment Manager could not be changed.');
    }
  }
  const [editing, setEditing] = useState<ProjectMember | null>(null);
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
      <div className="card-body spread" style={{ flexWrap: 'wrap', borderBottom: '1px solid var(--border)' }}>
        <div>
          <div className="label">Environment Manager</div>
          <div className="small">Registers the project&apos;s environments, repositories and branches. One per project.</div>
        </div>
        {admin && project.status !== 'ARCHIVED' && project.status !== 'DECLINED' ? (
          <select
            aria-label="Environment Manager"
            value={project.environmentManagerId ?? ''}
            onChange={(e) => void setManager(e.target.value)}
            style={{ width: 'auto', minWidth: 220 }}
          >
            <option value="">Not assigned</option>
            {(members.data ?? [])
              .filter((m) => m.userStatus !== 'DISABLED')
              .map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName}
                </option>
              ))}
          </select>
        ) : (
          <strong>{project.environmentManagerName ?? 'Not assigned'}</strong>
        )}
      </div>
      {members.isPending && <div className="card-body"><Loading /></div>}
      {members.isError && <div className="card-body"><ErrorText>Members could not be loaded.</ErrorText></div>}
      {members.data && list.length === 0 && <EmptyState icon="users" title="No members yet" />}
      {list.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Roles</th>
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
                      {m.userId === project.environmentManagerId && <Badge tone="violet">Environment Manager</Badge>}
                      {m.userStatus === 'DISABLED' && <Badge tone="neutral">Deactivated</Badge>}
                    </div>
                  </td>
                  <td>
                    <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
                      <RoleBadges roles={m.roles} />
                      {admin && (
                        <button type="button" className="btn-ghost btn-sm" aria-label={`Edit roles of ${m.displayName}`}
                          onClick={() => setEditing(m)}>
                          Edit
                        </button>
                      )}
                    </div>
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
      {editing && <EditRolesModal member={editing} onClose={() => setEditing(null)} onSaved={refresh} />}
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
  const canCreate = project.status === 'ACTIVE' && canRequest(project.myRoles);
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
          {project.myRoles?.length ? 'Requirements created in this project appear here.' : 'You are not a member of this project, so its requirements are not shown.'}
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
                    <StatePill state={r.state} designStatus={r.designStatus ?? null} />
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
      {(project.status === 'ACTIVE' || project.status === 'PENDING') && (
        <Card title="Archive project">
          <p className="small">
            Archiving stops new requirements and stops billing for this project. Members keep read access to existing
            requirements, and nothing is deleted.
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
  const [tab, setTab] = useState<'requirements' | 'environments' | 'members' | 'settings'>('requirements');
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
  ];
  if (p.myRoles?.length) tabs.push({ id: 'environments', label: 'Environments' });
  tabs.push({ id: 'members', label: 'Members', count: p.memberCount });
  if (isOrgAdmin) tabs.push({ id: 'settings', label: 'Settings' });

  return (
    <>
      <BackLink to="/projects">Projects</BackLink>
      <PageHeader
        title={
          <span className="row" style={{ gap: '0.75rem' }}>
            {p.name}
            {p.status === 'ARCHIVED' && <Badge tone="neutral">Archived</Badge>}
            {p.status === 'PENDING' && <Badge tone="warning">Awaiting Garuda approval</Badge>}
            {p.status === 'DECLINED' && <Badge tone="danger">Declined</Badge>}
          </span>
        }
        description={p.description || undefined}
        actions={
          <span className="row">
            <span className="small">Your role</span>
            <RoleBadges roles={p.myRoles} />
          </span>
        }
      />
      {p.status === 'PENDING' && (
        <Callout tone="warning" title="Awaiting Garuda approval">
          Garuda activates new projects before work starts. You can already add members; requirements can be created once the
          project is active.
        </Callout>
      )}
      {p.status === 'DECLINED' && <Callout tone="danger">Garuda declined this project. Contact your Garuda account team.</Callout>}
      {p.status === 'ACTIVE' && p.planName && isOrgAdmin && (
        <p className="small">
          Plan: {p.planName}
          {p.activatedAt ? ` · active since ${formatDate(p.activatedAt)}` : ''}
        </p>
      )}
      {isOrgAdmin && !p.myRoles?.length && (
        <Callout>
          You manage this project as Organisation Admin. To see its requirements, add yourself as a member (this is recorded
          in the audit history).
        </Callout>
      )}
      <Tabs label="Project sections" tabs={tabs} value={tab} onChange={setTab} />
      {tab === 'requirements' && <RequirementsTab project={p} />}
      {tab === 'environments' && !!p.myRoles?.length && <EnvironmentsTab project={p} />}
      {tab === 'members' && <MembersTab project={p} admin={isOrgAdmin} />}
      {tab === 'settings' && isOrgAdmin && <SettingsTab project={p} />}
    </>
  );
}
