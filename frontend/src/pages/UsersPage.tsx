import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { LinkResult, OrgRole, OrgUser, OrganisationSummary, ProjectList, ProjectRole } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../ui/Icon';
import { useToast } from '../ui/Toast';
import {
  ActionMenu,
  Avatar,
  Badge,
  Callout,
  CopyField,
  EmptyState,
  ErrorText,
  Loading,
  Modal,
  PageHeader,
  ROLE_LABELS,
  RoleBadge,
  Stat,
  formatDate,
  formatDateTime,
  relativeTime,
} from '../ui/components';
import { PROJECT_ROLES } from './ProjectDetailPage';

const ERRORS: Record<string, string> = {
  USER_EXISTS: 'Someone with this email is already in your organisation.',
  SELF_CHANGE_FORBIDDEN: 'Another Organisation Admin must make this change to your own account.',
  LAST_ORG_ADMIN: 'Your organisation must keep at least one active Organisation Admin.',
  USER_NOT_INVITED: 'This person has already accepted the invitation.',
  NO_OPEN_INVITATION: 'There is no open link for this person.',
  MFA_NOT_ENROLLED: 'This person has not set up an authenticator yet.',
  USER_NOT_ACTIVE: 'Only an active user can reset a password.',
};

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return ERRORS[error.code] ?? (error.status === 422 ? error.message : fallback);
  }
  return fallback;
}

function StatusBadge({ user }: { user: OrgUser }) {
  if (user.status === 'ACTIVE') return <Badge tone="success">Active</Badge>;
  if (user.status === 'DISABLED') return <Badge tone="neutral">Deactivated</Badge>;
  return <Badge tone="warning">Invited</Badge>;
}

function LinkResultModal({ result, title, onClose }: { result: LinkResult; title: string; onClose: () => void }) {
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <button type="button" className="btn-primary" onClick={onClose}>
          Done
        </button>
      }
    >
      {result.emailStatus === 'SENT' && (
        <Callout tone="success">We emailed the link. You can also copy it below and share it another way.</Callout>
      )}
      {result.emailStatus === 'FAILED' && (
        <Callout tone="warning">The email could not be delivered. Copy the link and send it to the person yourself.</Callout>
      )}
      {result.emailStatus === 'NOT_SENT' && (
        <Callout>Email is not set up for Garuda. Copy the link and send it to the person yourself.</Callout>
      )}
      <label>
        Personal link
        <CopyField value={result.link} label="Personal link" />
        <span className="hint">Works once and expires {formatDateTime(result.expiresAt)}. Share it only with this person.</span>
      </label>
    </Modal>
  );
}

interface MembershipRow {
  projectId: string;
  role: ProjectRole;
}

function InviteModal({ onClose, onInvited }: { onClose: () => void; onInvited: (r: LinkResult) => void }) {
  const queryClient = useQueryClient();
  const projects = useQuery({ queryKey: ['projects', 'active'], queryFn: () => api.get<ProjectList>('/api/v1/projects') });
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [role, setRole] = useState<OrgRole>('MEMBER');
  const [rows, setRows] = useState<MembershipRow[]>([]);
  const invite = useMutation({
    mutationFn: () =>
      api.post<LinkResult>('/api/v1/organisation/users', {
        email: email.trim(),
        displayName: name.trim(),
        role,
        memberships: rows.filter((r) => r.projectId),
      }),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['org-users'] });
      await queryClient.invalidateQueries({ queryKey: ['organisation'] });
      await queryClient.invalidateQueries({ queryKey: ['projects'] });
      onInvited(result);
    },
  });
  const available = (projects.data?.items ?? []).filter((p) => p.status !== 'ARCHIVED');
  const used = new Set(rows.map((r) => r.projectId));
  function submit(event: FormEvent) {
    event.preventDefault();
    invite.mutate();
  }
  return (
    <Modal
      wide
      title="Invite a person"
      description="They receive a personal link to set their password. Access follows the projects and roles you choose."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="invite-form" disabled={invite.isPending || !email.trim() || !name.trim()}>
            <Icon name="mail" size={16} />
            {invite.isPending ? 'Sending…' : 'Send invitation'}
          </button>
        </>
      }
    >
      <form id="invite-form" onSubmit={submit} noValidate>
        <div className="form-grid">
          <label>
            Full name
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} autoComplete="off" required />
          </label>
          <label>
            Work email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={320} autoComplete="off" required />
          </label>
        </div>
        <fieldset className="field">
          <legend>Organisation role</legend>
          <label className="check" style={{ alignItems: 'flex-start' }}>
            <input type="radio" name="org-role" checked={role === 'MEMBER'} onChange={() => setRole('MEMBER')} />
            <span>
              <strong>Member</strong>
              <span className="hint" style={{ display: 'block' }}>
                Works in the projects below, with the role given in each.
              </span>
            </span>
          </label>
          <label className="check" style={{ alignItems: 'flex-start' }}>
            <input type="radio" name="org-role" checked={role === 'ORG_ADMIN'} onChange={() => setRole('ORG_ADMIN')} />
            <span>
              <strong>Organisation Admin</strong>
              <span className="hint" style={{ display: 'block' }}>
                Manages users and projects. Must use an authenticator app. Sees project content only as a project member.
              </span>
            </span>
          </label>
        </fieldset>
        <div className="field">
          <span className="spread">
            <span>Project access</span>
            <button
              type="button"
              className="btn-ghost btn-sm"
              disabled={rows.length >= available.length}
              onClick={() => setRows((r) => [...r, { projectId: '', role: 'REQUESTER' }])}
            >
              <Icon name="plus" size={15} />
              Add project
            </button>
          </span>
          {rows.length === 0 && <span className="hint">No projects yet. You can also add people to projects later.</span>}
          {rows.map((row, i) => (
            <div key={i} className="row" style={{ flexWrap: 'nowrap' }}>
              <select
                aria-label={`Project ${i + 1}`}
                value={row.projectId}
                onChange={(e) => setRows((all) => all.map((r, j) => (j === i ? { ...r, projectId: e.target.value } : r)))}
              >
                <option value="">Choose a project…</option>
                {available
                  .filter((p) => p.id === row.projectId || !used.has(p.id))
                  .map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
              </select>
              <select
                aria-label={`Role in project ${i + 1}`}
                value={row.role}
                style={{ maxWidth: 170 }}
                onChange={(e) =>
                  setRows((all) => all.map((r, j) => (j === i ? { ...r, role: e.target.value as ProjectRole } : r)))
                }
              >
                {PROJECT_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="btn-ghost btn-icon"
                aria-label={`Remove project ${i + 1}`}
                onClick={() => setRows((all) => all.filter((_, j) => j !== i))}
              >
                <Icon name="x" size={16} />
              </button>
            </div>
          ))}
        </div>
        {invite.isError && <ErrorText>{errorMessage(invite.error, 'The invitation could not be sent.')}</ErrorText>}
      </form>
    </Modal>
  );
}

function RoleModal({ user, onClose }: { user: OrgUser; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [role, setRole] = useState<OrgRole>(user.role);
  const save = useMutation({
    mutationFn: () => api.put(`/api/v1/organisation/users/${user.id}/role`, { role }),
    onSuccess: async () => {
      toast.success(`${user.displayName} is now ${ROLE_LABELS[role]}.`);
      await queryClient.invalidateQueries({ queryKey: ['org-users'] });
      await queryClient.invalidateQueries({ queryKey: ['organisation'] });
      onClose();
    },
  });
  return (
    <Modal
      title={`Organisation role of ${user.displayName}`}
      description="Changing the role signs the person out; they sign in again with the new role."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn-primary" disabled={role === user.role || save.isPending} onClick={() => save.mutate()}>
            Save role
          </button>
        </>
      }
    >
      {(['MEMBER', 'ORG_ADMIN'] as OrgRole[]).map((r) => (
        <label key={r} className="check">
          <input type="radio" name="role" checked={role === r} onChange={() => setRole(r)} />
          {ROLE_LABELS[r]}
        </label>
      ))}
      {save.isError && <ErrorText>{errorMessage(save.error, 'The role could not be changed.')}</ErrorText>}
    </Modal>
  );
}

/** Organisation user administration (FR-ORG-04..08). Every action is checked again by the server and the database. */
export function UsersPage() {
  const { me } = useAuth();
  const queryClient = useQueryClient();
  const toast = useToast();
  const summary = useQuery({ queryKey: ['organisation'], queryFn: () => api.get<OrganisationSummary>('/api/v1/organisation') });
  const users = useQuery({ queryKey: ['org-users'], queryFn: () => api.get<OrgUser[]>('/api/v1/organisation/users') });
  const [inviting, setInviting] = useState(false);
  const [link, setLink] = useState<{ title: string; result: LinkResult } | null>(null);
  const [roleFor, setRoleFor] = useState<OrgUser | null>(null);
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<'ALL' | 'ACTIVE' | 'INVITED' | 'DISABLED'>('ALL');

  const list = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (users.data ?? []).filter(
      (u) =>
        (filter === 'ALL' || u.status === filter) &&
        (!q || u.displayName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)),
    );
  }, [users.data, search, filter]);

  async function act(action: () => Promise<unknown>, success: string, failure: string) {
    try {
      await action();
      toast.success(success);
      await queryClient.invalidateQueries({ queryKey: ['org-users'] });
      await queryClient.invalidateQueries({ queryKey: ['organisation'] });
    } catch (e) {
      toast.error(errorMessage(e, failure));
    }
  }

  async function issue(user: OrgUser, path: string, title: string) {
    try {
      const result = await api.post<LinkResult>(`/api/v1/organisation/users/${user.id}/${path}`);
      setLink({ title, result });
      await queryClient.invalidateQueries({ queryKey: ['org-users'] });
    } catch (e) {
      toast.error(errorMessage(e, 'The link could not be created.'));
    }
  }

  const s = summary.data;
  const loginUrl = s ? `${window.location.origin}/o/${s.slug}/login` : '';
  return (
    <>
      <PageHeader
        eyebrow="Administration"
        title="Users"
        description="Invite people, decide their organisation role and the projects they work in."
        actions={
          <button type="button" className="btn-primary" onClick={() => setInviting(true)}>
            <Icon name="userPlus" size={16} />
            Invite person
          </button>
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat icon="users" label="Active users" value={s?.activeUsers ?? '–'} />
        <Stat icon="mail" label="Pending invitations" value={s?.invitedUsers ?? '–'} />
        <Stat icon="shield" label="Organisation Admins" value={s?.organisationAdmins ?? '–'} />
        <Stat icon="lock" label="Deactivated" value={s?.deactivatedUsers ?? '–'} />
      </div>
      {s && (
        <div className="card">
          <div className="spread" style={{ flexWrap: 'wrap' }}>
            <div>
              <div className="label">Sign-in address for your organisation</div>
              <div className="small">Share it with your team. Sign-in code: <code>{s.slug}</code></div>
            </div>
            <div style={{ minWidth: 'min(420px, 100%)' }}>
              <CopyField value={loginUrl} label="Sign-in address" />
            </div>
          </div>
        </div>
      )}
      <div className="card card-flush">
        <div className="card-header" style={{ flexWrap: 'wrap' }}>
          <div className="row">
            {(['ALL', 'ACTIVE', 'INVITED', 'DISABLED'] as const).map((f) => (
              <button
                key={f}
                type="button"
                className={`btn-sm ${filter === f ? 'btn-secondary' : 'btn-ghost'}`}
                aria-pressed={filter === f}
                onClick={() => setFilter(f)}
              >
                {f === 'ALL' ? 'All' : f === 'ACTIVE' ? 'Active' : f === 'INVITED' ? 'Invited' : 'Deactivated'}
              </button>
            ))}
          </div>
          <input
            type="search"
            placeholder="Search name or email"
            aria-label="Search users"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ maxWidth: 280 }}
          />
        </div>
        {users.isPending && <div className="card-body"><Loading label="Loading users…" /></div>}
        {users.isError && <div className="card-body"><ErrorText>Users could not be loaded.</ErrorText></div>}
        {users.data && list.length === 0 && <EmptyState icon="users" title="No matching users" />}
        {list.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Person</th>
                  <th scope="col">Organisation role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Projects</th>
                  <th scope="col">Security</th>
                  <th scope="col" className="num">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.map((u) => {
                  const self = u.id === me?.userId;
                  return (
                    <tr key={u.id}>
                      <td>
                        <div className="row" style={{ flexWrap: 'nowrap' }}>
                          <Avatar name={u.displayName} />
                          <div>
                            <span className="primary-cell">
                              {u.displayName}
                              {self && <span className="muted"> (you)</span>}
                            </span>
                            <span className="sub">{u.email}</span>
                          </div>
                        </div>
                      </td>
                      <td>
                        <RoleBadge role={u.role} />
                      </td>
                      <td>
                        <StatusBadge user={u} />
                        <span className="sub">
                          {u.status === 'INVITED' && u.invitation
                            ? `${u.invitation.emailStatus === 'SENT' ? 'Emailed' : u.invitation.emailStatus === 'FAILED' ? 'Email failed' : 'Link created'} ${relativeTime(u.invitation.createdAt)}`
                            : u.status === 'INVITED'
                              ? 'Link expired'
                              : u.activatedAt
                                ? `Since ${formatDate(u.activatedAt)}`
                                : ''}
                        </span>
                      </td>
                      <td>
                        {u.memberships.length === 0 ? (
                          <span className="muted small">None</span>
                        ) : (
                          <div className="row" style={{ gap: '0.3rem' }}>
                            {u.memberships.slice(0, 3).map((m) => (
                              <Badge key={m.memberId} tone="neutral" dot={false}>
                                {m.projectName} · {ROLE_LABELS[m.role]}
                              </Badge>
                            ))}
                            {u.memberships.length > 3 && <span className="small">+{u.memberships.length - 3}</span>}
                          </div>
                        )}
                      </td>
                      <td>
                        {u.mfaEnrolled ? (
                          <Badge tone="success">Authenticator</Badge>
                        ) : u.role === 'ORG_ADMIN' ? (
                          <Badge tone="warning">Setup at sign-in</Badge>
                        ) : (
                          <span className="muted small">Password</span>
                        )}
                      </td>
                      <td className="num">
                        {!self && (
                          <ActionMenu label={`Actions for ${u.displayName}`}>
                            {(close) => (
                              <>
                                {u.status === 'INVITED' && (
                                  <>
                                    <button
                                      type="button"
                                      role="menuitem"
                                      onClick={() => {
                                        close();
                                        void issue(u, 'invitation/resend', `New invitation for ${u.displayName}`);
                                      }}
                                    >
                                      <Icon name="refresh" size={16} />
                                      Resend invitation
                                    </button>
                                    <button
                                      type="button"
                                      role="menuitem"
                                      onClick={() => {
                                        close();
                                        void act(
                                          () => api.post(`/api/v1/organisation/users/${u.id}/invitation/revoke`),
                                          'Invitation revoked.',
                                          'The invitation could not be revoked.',
                                        );
                                      }}
                                    >
                                      <Icon name="x" size={16} />
                                      Revoke invitation
                                    </button>
                                  </>
                                )}
                                <button
                                  type="button"
                                  role="menuitem"
                                  onClick={() => {
                                    close();
                                    setRoleFor(u);
                                  }}
                                >
                                  <Icon name="shield" size={16} />
                                  Change organisation role
                                </button>
                                {u.status === 'ACTIVE' && (
                                  <button
                                    type="button"
                                    role="menuitem"
                                    onClick={() => {
                                      close();
                                      void issue(u, 'password-reset', `Password reset for ${u.displayName}`);
                                    }}
                                  >
                                    <Icon name="key" size={16} />
                                    Send password reset
                                  </button>
                                )}
                                {u.mfaEnrolled && (
                                  <button
                                    type="button"
                                    role="menuitem"
                                    onClick={() => {
                                      close();
                                      if (window.confirm(`Reset the authenticator of ${u.displayName}? They set it up again at next sign-in.`)) {
                                        void act(
                                          () => api.post(`/api/v1/organisation/users/${u.id}/mfa/reset`),
                                          'Authenticator reset.',
                                          'The authenticator could not be reset.',
                                        );
                                      }
                                    }}
                                  >
                                    <Icon name="refresh" size={16} />
                                    Reset authenticator
                                  </button>
                                )}
                                <div className="menu-sep" />
                                {u.status === 'ACTIVE' && (
                                  <button
                                    type="button"
                                    role="menuitem"
                                    className="danger"
                                    onClick={() => {
                                      close();
                                      if (window.confirm(`Deactivate ${u.displayName}? They are signed out and cannot sign in.`)) {
                                        void act(
                                          () => api.post(`/api/v1/organisation/users/${u.id}/deactivate`),
                                          `${u.displayName} was deactivated.`,
                                          'The user could not be deactivated.',
                                        );
                                      }
                                    }}
                                  >
                                    <Icon name="lock" size={16} />
                                    Deactivate
                                  </button>
                                )}
                                {u.status === 'DISABLED' && (
                                  <button
                                    type="button"
                                    role="menuitem"
                                    onClick={() => {
                                      close();
                                      void act(
                                        () => api.post(`/api/v1/organisation/users/${u.id}/reactivate`),
                                        `${u.displayName} was reactivated.`,
                                        'The user could not be reactivated.',
                                      );
                                    }}
                                  >
                                    <Icon name="play" size={16} />
                                    Reactivate
                                  </button>
                                )}
                              </>
                            )}
                          </ActionMenu>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {inviting && (
        <InviteModal
          onClose={() => setInviting(false)}
          onInvited={(result) => {
            setInviting(false);
            setLink({ title: 'Invitation sent', result });
          }}
        />
      )}
      {link && <LinkResultModal title={link.title} result={link.result} onClose={() => setLink(null)} />}
      {roleFor && <RoleModal user={roleFor} onClose={() => setRoleFor(null)} />}
    </>
  );
}
