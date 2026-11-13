import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { StaffApproved, StaffMember, StaffRequest, StaffRequestKind } from '../../api/types';
import { useStaffMe } from '../../components/StaffLayout';
import { Icon } from '../../ui/Icon';
import { useToast } from '../../ui/Toast';
import {
  ActionMenu,
  Avatar,
  Badge,
  Callout,
  Card,
  CopyField,
  EmptyState,
  ErrorText,
  Loading,
  Modal,
  PageHeader,
  Stat,
  Tabs,
  formatDateTime,
  relativeTime,
  type Tone,
} from '../../ui/components';

/** What each capability lets a staff member do, shown when inviting and granting. */
export const CAPABILITIES: { id: string; label: string; help: string }[] = [
  { id: 'STAFF_ADMIN', label: 'Staff administrator', help: 'Requests and approves changes to Garuda staff; sees the dashboard.' },
  { id: 'ORG_ONBOARD', label: 'Organisation onboarding', help: 'Onboards, suspends and reactivates client organisations.' },
  { id: 'AUDITOR', label: 'Auditor', help: 'Read-only access to the dashboard.' },
  { id: 'BUSINESS_ANALYST', label: 'Business analyst', help: 'Assesses client change requests.' },
  { id: 'BUSINESS_REVIEWER', label: 'Business reviewer', help: 'Reviews and approves BRD versions.' },
  { id: 'RELEASE_APPROVE', label: 'Release approver', help: 'Releases approved BRDs to the client.' },
  { id: 'REPOSITORY_REGISTER', label: 'Repository registration', help: 'Registers repositories that a client authorised.' },
  { id: 'JOB_RETRY', label: 'Job retry', help: 'Retries failed background jobs after review.' },
  { id: 'FEATURE_FLAG_ACK', label: 'Feature flags', help: 'Acknowledges feature-flag changes.' },
  { id: 'TENANT_DELETE', label: 'Organisation deletion', help: 'Verifies or approves organisation deletion and restoration.' },
];

const CAPABILITY_LABEL = Object.fromEntries(CAPABILITIES.map((c) => [c.id, c.label]));

const KIND_LABEL: Record<StaffRequestKind, string> = {
  INVITE: 'Invite',
  GRANT_CAPABILITY: 'Grant capability',
  REVOKE_CAPABILITY: 'Revoke capability',
  ASSIGN_TENANT: 'Assign organisation',
  REVOKE_ASSIGNMENT: 'Remove organisation',
  DISABLE: 'Disable',
  REACTIVATE: 'Reactivate',
  RESET_MFA: 'Reset authenticator',
  RESET_PASSWORD: 'Reset password',
};

const STATUS_TONE: Record<string, Tone> = { REQUESTED: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'neutral' };

const ERRORS: Record<string, string> = {
  SECOND_APPROVER_REQUIRED: 'A different staff administrator must approve this request.',
  SELF_CHANGE_FORBIDDEN: 'Changes to your own account must be requested and approved by two other administrators.',
  STAFF_EXISTS: 'A staff member with this email already exists.',
  REQUEST_OPEN: 'An invitation for this email is already waiting for approval.',
  REQUEST_STATE: 'This request has already been decided.',
};

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return ERRORS[error.code] ?? (error.status === 409 || error.status === 422 ? error.message : fallback);
  return fallback;
}

function describe(r: StaffRequest): string {
  const who = r.kind === 'INVITE' ? `${r.inviteDisplayName} (${r.inviteEmail})` : (r.subjectName ?? 'staff member');
  switch (r.kind) {
    case 'INVITE':
      return `Invite ${who}${r.inviteCapabilities.length ? ` as ${r.inviteCapabilities.map((c) => CAPABILITY_LABEL[c] ?? c).join(', ')}` : ''}`;
    case 'GRANT_CAPABILITY':
      return `Give ${who} “${CAPABILITY_LABEL[r.capability ?? ''] ?? r.capability}”`;
    case 'REVOKE_CAPABILITY':
      return `Remove “${CAPABILITY_LABEL[r.capability ?? ''] ?? r.capability}” from ${who}`;
    case 'ASSIGN_TENANT':
      return `Assign ${who} to ${r.tenantName}`;
    case 'REVOKE_ASSIGNMENT':
      return `Remove ${who} from ${r.tenantName}`;
    default:
      return `${KIND_LABEL[r.kind]}: ${who}`;
  }
}

interface RequestDraft {
  kind: StaffRequestKind;
  member?: StaffMember;
}

/** One form for every request type: who, what, and a reason that goes into the audit history. */
function RequestModal({ draft, onClose }: { draft: RequestDraft; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const tenants = useQuery({
    queryKey: ['staff-admin-tenants'],
    queryFn: () => api.get<{ id: string; name: string }[]>('/api/v1/staff/admin/tenants'),
    enabled: draft.kind === 'ASSIGN_TENANT',
  });
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [caps, setCaps] = useState<string[]>([]);
  const [capability, setCapability] = useState('');
  const [tenantId, setTenantId] = useState('');
  const [reason, setReason] = useState('');
  const m = draft.member;
  const held = new Set(m?.capabilities ?? []);
  const capabilityChoices =
    draft.kind === 'REVOKE_CAPABILITY' ? CAPABILITIES.filter((c) => held.has(c.id)) : CAPABILITIES.filter((c) => !held.has(c.id));
  const tenantChoices =
    draft.kind === 'REVOKE_ASSIGNMENT'
      ? (m?.assignments ?? []).map((a) => ({ id: a.tenantId, name: a.tenantName }))
      : (tenants.data ?? []).filter((t) => !(m?.assignments ?? []).some((a) => a.tenantId === t.id));

  const create = useMutation({
    mutationFn: () =>
      api.post<StaffRequest>('/api/v1/staff/admin/requests', {
        kind: draft.kind,
        subjectStaffId: m?.id ?? null,
        email: email.trim() || null,
        displayName: name.trim() || null,
        capabilities: caps,
        capability: capability || null,
        tenantId: tenantId || null,
        reason: reason.trim(),
      }),
    onSuccess: async () => {
      toast.success('Request recorded. A second staff administrator must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['staff-admin-requests'] });
      onClose();
    },
  });
  const needsCapability = draft.kind === 'GRANT_CAPABILITY' || draft.kind === 'REVOKE_CAPABILITY';
  const needsTenant = draft.kind === 'ASSIGN_TENANT' || draft.kind === 'REVOKE_ASSIGNMENT';
  const ready =
    reason.trim().length >= 5 &&
    (draft.kind !== 'INVITE' || (email.trim() && name.trim())) &&
    (!needsCapability || capability) &&
    (!needsTenant || tenantId);
  const title = draft.kind === 'INVITE' ? 'Invite a staff member' : `${KIND_LABEL[draft.kind]}: ${m?.displayName}`;
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }
  return (
    <Modal
      wide={draft.kind === 'INVITE'}
      title={title}
      description="Every staff change is approved by a second staff administrator before it takes effect."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="staff-request" disabled={!ready || create.isPending} className={draft.kind === 'DISABLE' ? 'btn-danger' : undefined}>
            Request approval
          </button>
        </>
      }
    >
      <form id="staff-request" onSubmit={submit} noValidate>
        {draft.kind === 'INVITE' && (
          <>
            <div className="form-grid">
              <label>
                Full name
                <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} required />
              </label>
              <label>
                Work email
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={320} required />
              </label>
            </div>
            <fieldset className="field">
              <legend>Capabilities</legend>
              <div className="grid grid-2" style={{ gap: '0.25rem 1rem' }}>
                {CAPABILITIES.map((c) => (
                  <label key={c.id} className="check" style={{ alignItems: 'flex-start' }}>
                    <input
                      type="checkbox"
                      checked={caps.includes(c.id)}
                      onChange={(e) => setCaps((all) => (e.target.checked ? [...all, c.id] : all.filter((x) => x !== c.id)))}
                    />
                    <span>
                      <strong>{c.label}</strong>
                      <span className="hint" style={{ display: 'block' }}>
                        {c.help}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          </>
        )}
        {needsCapability && (
          <label>
            Capability
            <select value={capability} onChange={(e) => setCapability(e.target.value)}>
              <option value="">Choose…</option>
              {capabilityChoices.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
        )}
        {needsTenant && (
          <label>
            Organisation
            <select value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
              <option value="">Choose…</option>
              {tenantChoices.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {draft.kind === 'DISABLE' && (
          <Callout tone="warning">Once approved, they are signed out and cannot sign in. Nothing is deleted.</Callout>
        )}
        {draft.kind === 'RESET_MFA' && (
          <Callout>Once approved, they set up a new authenticator at their next sign-in (within 72 hours).</Callout>
        )}
        {draft.kind === 'RESET_PASSWORD' && (
          <Callout>Once approved, the approver receives a single-use link to send to them.</Callout>
        )}
        <label>
          Reason
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={2000} />
          <span className="hint">At least 5 characters. Recorded in the platform audit history.</span>
        </label>
        {create.isError && <ErrorText>{message(create.error, 'The request could not be recorded.')}</ErrorText>}
      </form>
    </Modal>
  );
}

function LinkModal({ result, onClose }: { result: StaffApproved; onClose: () => void }) {
  return (
    <Modal
      title="Link created"
      onClose={onClose}
      footer={
        <button type="button" className="btn-primary" onClick={onClose}>
          Done
        </button>
      }
    >
      {result.emailStatus === 'SENT' ? (
        <Callout tone="success">We emailed the link. You can also copy it below.</Callout>
      ) : (
        <Callout tone="warning">
          {result.emailStatus === 'FAILED' ? 'The email could not be delivered.' : 'Email is not set up.'} Send this link to the
          person yourself.
        </Callout>
      )}
      <label>
        Personal link
        <CopyField value={result.link ?? ''} label="Personal link" />
        <span className="hint">Works once and expires in 72 hours. Share it only with this person.</span>
      </label>
    </Modal>
  );
}

/** Garuda staff administration (GD-15): invite and manage staff, each change approved by a second administrator. */
export function StaffTeamPage() {
  const me = useStaffMe();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [tab, setTab] = useState<'people' | 'requests'>('people');
  const [draft, setDraft] = useState<RequestDraft | null>(null);
  const [link, setLink] = useState<StaffApproved | null>(null);
  const [search, setSearch] = useState('');
  const staff = useQuery({ queryKey: ['staff-admin-staff'], queryFn: () => api.get<StaffMember[]>('/api/v1/staff/admin/staff') });
  const requests = useQuery({
    queryKey: ['staff-admin-requests'],
    queryFn: () => api.get<StaffRequest[]>('/api/v1/staff/admin/requests'),
  });
  const myId = me.data?.staffId;
  const open = (requests.data ?? []).filter((r) => r.status === 'REQUESTED');
  const awaitingMe = open.filter((r) => r.requestedBy !== myId && r.subjectStaffId !== myId);
  const people = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (staff.data ?? []).filter((s) => !q || s.displayName.toLowerCase().includes(q) || s.email.toLowerCase().includes(q));
  }, [staff.data, search]);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['staff-admin-requests'] });
    await queryClient.invalidateQueries({ queryKey: ['staff-admin-staff'] });
  }

  async function approve(r: StaffRequest) {
    if (!window.confirm(`Approve: ${describe(r)}?`)) return;
    try {
      const result = await api.post<StaffApproved>(`/api/v1/staff/admin/requests/${r.id}/approve`);
      toast.success('Approved and applied.');
      if (result.link) setLink(result);
      await refresh();
    } catch (e) {
      toast.error(message(e, 'The request could not be approved.'));
    }
  }

  async function decide(r: StaffRequest, action: 'reject' | 'cancel') {
    try {
      await api.post(`/api/v1/staff/admin/requests/${r.id}/${action}`);
      toast.success(action === 'reject' ? 'Request rejected.' : 'Request cancelled.');
      await refresh();
    } catch (e) {
      toast.error(message(e, 'The decision could not be recorded.'));
    }
  }

  async function resend(m: StaffMember) {
    try {
      setLink(await api.post<StaffApproved>(`/api/v1/staff/admin/staff/${m.id}/invitation/resend`));
      await refresh();
    } catch (e) {
      toast.error(message(e, 'The invitation could not be resent.'));
    }
  }

  if (staff.isError && staff.error instanceof ApiError && staff.error.status === 403) {
    return <ErrorText>Only staff administrators can manage the Garuda team.</ErrorText>;
  }
  const list = staff.data ?? [];
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Team"
        description="Garuda staff and what they may do. Every change is requested by one staff administrator and approved by another."
        actions={
          <button type="button" className="btn-primary" onClick={() => setDraft({ kind: 'INVITE' })}>
            <Icon name="userPlus" size={16} />
            Invite staff member
          </button>
        }
      />
      <div className="grid grid-4" style={{ marginBottom: '1.25rem' }}>
        <Stat icon="users" label="Active staff" value={list.filter((s) => s.status === 'ACTIVE').length} />
        <Stat icon="mail" label="Invited" value={list.filter((s) => s.status === 'INVITED').length} />
        <Stat icon="shield" label="Staff administrators" value={list.filter((s) => s.status === 'ACTIVE' && s.capabilities.includes('STAFF_ADMIN')).length} />
        <Stat icon="checkCircle" label="Waiting for your approval" value={awaitingMe.length} />
      </div>
      <Tabs
        label="Team sections"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'people', label: 'People', count: list.length },
          { id: 'requests', label: 'Requests', count: open.length },
        ]}
      />
      {tab === 'people' && (
        <div className="card card-flush">
          <div className="card-header">
            <span className="small">Only the first two administrators are set up on the command line; everyone else is invited here.</span>
            <input
              type="search"
              placeholder="Search name or email"
              aria-label="Search staff"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ maxWidth: 260 }}
            />
          </div>
          {staff.isPending && <div className="card-body"><Loading /></div>}
          {staff.data && people.length === 0 && <EmptyState icon="users" title="No staff found" />}
          {people.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Person</th>
                    <th scope="col">Status</th>
                    <th scope="col">Capabilities</th>
                    <th scope="col">Organisations</th>
                    <th scope="col">Authenticator</th>
                    <th scope="col" className="num">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {people.map((s) => {
                    const self = s.id === myId;
                    return (
                      <tr key={s.id}>
                        <td>
                          <div className="row" style={{ flexWrap: 'nowrap' }}>
                            <Avatar name={s.displayName} />
                            <div>
                              <span className="primary-cell">
                                {s.displayName}
                                {self && <span className="muted"> (you)</span>}
                              </span>
                              <span className="sub">{s.email}</span>
                            </div>
                          </div>
                        </td>
                        <td>
                          {s.status === 'ACTIVE' ? (
                            <Badge tone="success">Active</Badge>
                          ) : s.status === 'INVITED' ? (
                            <Badge tone="warning">Invited</Badge>
                          ) : (
                            <Badge tone="neutral">Disabled</Badge>
                          )}
                          {s.pendingLink && (
                            <span className="sub">
                              {s.pendingLink.purpose === 'INVITE' ? 'Invitation' : 'Reset link'} expires {relativeTime(s.pendingLink.expiresAt).replace('in ', 'in ')}
                            </span>
                          )}
                        </td>
                        <td>
                          {s.capabilities.length === 0 ? (
                            <span className="muted small">None</span>
                          ) : (
                            <div className="row" style={{ gap: '0.3rem' }}>
                              {s.capabilities.map((c) => (
                                <Badge key={c} tone={c === 'STAFF_ADMIN' ? 'primary' : 'neutral'} dot={false}>
                                  {CAPABILITY_LABEL[c] ?? c}
                                </Badge>
                              ))}
                            </div>
                          )}
                        </td>
                        <td className="small">
                          {s.assignments.length === 0 ? <span className="muted">None</span> : s.assignments.map((a) => a.tenantName).join(', ')}
                        </td>
                        <td>{s.mfaEnrolled ? <Badge tone="success">Enrolled</Badge> : <Badge tone="warning">Not set up</Badge>}</td>
                        <td className="num">
                          {!self && (
                            <ActionMenu label={`Actions for ${s.displayName}`}>
                              {(close) => {
                                const item = (kind: StaffRequestKind, icon: Parameters<typeof Icon>[0]['name'], label: string, danger = false) => (
                                  <button
                                    type="button"
                                    role="menuitem"
                                    className={danger ? 'danger' : undefined}
                                    onClick={() => {
                                      close();
                                      setDraft({ kind, member: s });
                                    }}
                                  >
                                    <Icon name={icon} size={16} />
                                    {label}
                                  </button>
                                );
                                return (
                                  <>
                                    {s.status === 'INVITED' && (
                                      <button
                                        type="button"
                                        role="menuitem"
                                        onClick={() => {
                                          close();
                                          void resend(s);
                                        }}
                                      >
                                        <Icon name="refresh" size={16} />
                                        Resend invitation
                                      </button>
                                    )}
                                    {s.status !== 'DISABLED' && item('GRANT_CAPABILITY', 'plus', 'Grant capability')}
                                    {s.capabilities.length > 0 && item('REVOKE_CAPABILITY', 'x', 'Revoke capability')}
                                    {s.status !== 'DISABLED' && item('ASSIGN_TENANT', 'building', 'Assign organisation')}
                                    {s.assignments.length > 0 && item('REVOKE_ASSIGNMENT', 'x', 'Remove organisation')}
                                    {s.status === 'ACTIVE' && item('RESET_PASSWORD', 'key', 'Reset password')}
                                    {s.status === 'ACTIVE' && s.mfaEnrolled && item('RESET_MFA', 'refresh', 'Reset authenticator')}
                                    <div className="menu-sep" />
                                    {s.status === 'ACTIVE' && item('DISABLE', 'lock', 'Disable', true)}
                                    {s.status === 'DISABLED' && item('REACTIVATE', 'play', 'Reactivate')}
                                  </>
                                );
                              }}
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
      )}
      {tab === 'requests' && (
        <Card title="Requests" flush>
          {requests.isPending && <div className="card-body"><Loading /></div>}
          {requests.data && requests.data.length === 0 && <EmptyState icon="checkCircle" title="No requests yet" />}
          {requests.data && requests.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Change</th>
                    <th scope="col">Reason</th>
                    <th scope="col">Requested</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="num">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {requests.data.map((r) => {
                    const mine = r.requestedBy === myId;
                    const aboutMe = r.subjectStaffId === myId;
                    return (
                      <tr key={r.id}>
                        <td>
                          <span className="primary-cell">{describe(r)}</span>
                          <span className="sub">{KIND_LABEL[r.kind]}</span>
                        </td>
                        <td className="small" style={{ maxWidth: 280 }}>
                          {r.reason}
                        </td>
                        <td>
                          {r.requestedByName}
                          {mine && <span className="muted"> (you)</span>}
                          <span className="sub">{relativeTime(r.requestedAt)}</span>
                        </td>
                        <td>
                          <Badge tone={STATUS_TONE[r.status] ?? 'neutral'}>
                            {r.status === 'REQUESTED' ? 'Awaiting approval' : r.status.charAt(0) + r.status.slice(1).toLowerCase()}
                          </Badge>
                          {r.status === 'EXECUTED' && (
                            <span className="sub">
                              by {r.approvedByName} · {formatDateTime(r.executedAt)}
                            </span>
                          )}
                        </td>
                        <td className="num">
                          {r.status === 'REQUESTED' &&
                            (mine ? (
                              <div className="row" style={{ justifyContent: 'flex-end' }}>
                                <span className="small">Needs a second approver</span>
                                <button type="button" className="btn-ghost btn-sm" onClick={() => void decide(r, 'cancel')}>
                                  Cancel
                                </button>
                              </div>
                            ) : aboutMe ? (
                              <span className="small">About you: another administrator decides</span>
                            ) : (
                              <div className="row" style={{ justifyContent: 'flex-end' }}>
                                <button type="button" className="btn-secondary btn-sm" onClick={() => void decide(r, 'reject')}>
                                  Reject
                                </button>
                                <button type="button" className="btn-primary btn-sm" onClick={() => void approve(r)}>
                                  <Icon name="check" size={15} />
                                  Approve
                                </button>
                              </div>
                            ))}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      {draft && <RequestModal draft={draft} onClose={() => setDraft(null)} />}
      {link && <LinkModal result={link} onClose={() => setLink(null)} />}
    </>
  );
}
