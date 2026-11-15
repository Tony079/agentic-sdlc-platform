import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { OrgAdminRequest, OrgAdminRequestKind, OrganisationProfile, OrganisationRecord } from '../../api/types';
import { EMPTY_PROFILE, OrganisationProfileForm, OrganisationProfileView, cleanProfile } from '../../components/OrganisationProfileForm';
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
  formatDateTime,
  relativeTime,
  type Tone,
} from '../../ui/components';

const KIND_LABEL: Record<OrgAdminRequestKind, string> = {
  INVITE_ADMIN: 'Invite Organisation Admin',
  DISABLE_ADMIN: 'Disable Organisation Admin',
  REACTIVATE_ADMIN: 'Reactivate Organisation Admin',
  RESET_ADMIN_MFA: 'Reset admin authenticator',
  UPDATE_PROFILE: 'Update organisation record',
};

const STATUS_TONE: Record<string, Tone> = { REQUESTED: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'neutral' };

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'SECOND_APPROVER_REQUIRED') return 'A different Garuda Admin must approve this request.';
    if (error.status === 403) return 'Only Garuda Admins can do this.';
    if (error.status === 409 || error.status === 422) return error.message;
  }
  return fallback;
}

export function describeOrgRequest(r: OrgAdminRequest): string {
  if (r.kind === 'INVITE_ADMIN') return `Invite ${r.inviteDisplayName} (${r.inviteEmail}) as Organisation Admin`;
  if (r.kind === 'UPDATE_PROFILE') return 'Update the organisation record';
  return `${KIND_LABEL[r.kind]}: ${r.subjectName ?? 'admin'}`;
}

/** Requests with approve, reject and cancel; the requester never sees Approve on their own request. */
export function OrgAdminRequestTable({ requests, showOrganisation }: { requests: OrgAdminRequest[]; showOrganisation?: boolean }) {
  const me = useStaffMe();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [link, setLink] = useState<{ link: string; emailStatus: string } | null>(null);
  async function act(r: OrgAdminRequest, action: 'approve' | 'reject' | 'cancel') {
    if (action === 'approve' && !window.confirm(`Approve: ${describeOrgRequest(r)}?`)) return;
    try {
      const result = await api.post<{ link: string | null; emailStatus: string | null }>(
        `/api/v1/staff/organisation-admin-requests/${r.id}/${action}`,
      );
      toast.success(action === 'approve' ? 'Approved and applied.' : action === 'reject' ? 'Request rejected.' : 'Request cancelled.');
      if (action === 'approve' && result?.link) setLink({ link: result.link, emailStatus: result.emailStatus ?? 'NOT_SENT' });
      await queryClient.invalidateQueries({ queryKey: ['org-record'] });
      await queryClient.invalidateQueries({ queryKey: ['org-admin-requests'] });
      await queryClient.invalidateQueries({ queryKey: ['staff-dashboard'] });
    } catch (e) {
      toast.error(message(e, 'The decision could not be recorded.'));
    }
  }
  if (requests.length === 0) return <EmptyState icon="checkCircle" title="No requests" />;
  return (
    <>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Change</th>
              {showOrganisation && <th scope="col">Organisation</th>}
              <th scope="col">Reason</th>
              <th scope="col">Requested</th>
              <th scope="col">Status</th>
              <th scope="col" className="num">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {requests.map((r) => {
              const mine = r.requestedBy === me.data?.staffId;
              return (
                <tr key={r.id}>
                  <td>
                    <span className="primary-cell">{describeOrgRequest(r)}</span>
                  </td>
                  {showOrganisation && (
                    <td>
                      <Link to={`/staff/organisations/${r.tenantId}`}>{r.organisationName}</Link>
                    </td>
                  )}
                  <td className="small" style={{ maxWidth: 260 }}>
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
                          <button type="button" className="btn-ghost btn-sm" onClick={() => void act(r, 'cancel')}>
                            Cancel
                          </button>
                        </div>
                      ) : (
                        <div className="row" style={{ justifyContent: 'flex-end' }}>
                          <button type="button" className="btn-secondary btn-sm" onClick={() => void act(r, 'reject')}>
                            Reject
                          </button>
                          <button type="button" className="btn-primary btn-sm" onClick={() => void act(r, 'approve')}>
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
      {link && (
        <Modal
          title="Invitation link"
          onClose={() => setLink(null)}
          footer={
            <button type="button" className="btn-primary" onClick={() => setLink(null)}>
              Done
            </button>
          }
        >
          {link.emailStatus === 'SENT' ? (
            <Callout tone="success">We emailed the invitation. You can also copy it below.</Callout>
          ) : (
            <Callout tone="warning">The invitation was not emailed. Send this link to the admin yourself.</Callout>
          )}
          <label>
            Invitation link
            <CopyField value={link.link} label="Invitation link" />
          </label>
        </Modal>
      )}
    </>
  );
}

interface Draft {
  kind: OrgAdminRequestKind;
  admin?: OrganisationRecord['admins'][number];
}

function RequestModal({ record, draft, onClose }: { record: OrganisationRecord; draft: Draft; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [profile, setProfile] = useState<OrganisationProfile>(record.profile ?? { ...EMPTY_PROFILE, legalName: record.name, displayName: record.name });
  const create = useMutation({
    mutationFn: () =>
      api.post<OrgAdminRequest>(`/api/v1/staff/organisations/${record.tenantId}/admin-requests`, {
        kind: draft.kind,
        subjectUserId: draft.admin?.id ?? null,
        email: email.trim() || null,
        displayName: name.trim() || null,
        profile: draft.kind === 'UPDATE_PROFILE' ? cleanProfile({ ...profile, allowedEmailDomains: profile.allowedEmailDomains.filter(Boolean) }) : null,
        reason: reason.trim(),
      }),
    onSuccess: async () => {
      toast.success('Request recorded. A second Garuda Admin must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['org-record', record.tenantId] });
      await queryClient.invalidateQueries({ queryKey: ['org-admin-requests'] });
      onClose();
    },
  });
  const ready = reason.trim().length >= 5 && (draft.kind !== 'INVITE_ADMIN' || (email.trim() && name.trim()));
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }
  return (
    <Modal
      wide={draft.kind === 'UPDATE_PROFILE'}
      title={draft.admin ? `${KIND_LABEL[draft.kind]}: ${draft.admin.displayName}` : KIND_LABEL[draft.kind]}
      description="A second Garuda Admin approves the request before it takes effect."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="org-admin-request" disabled={!ready || create.isPending} className={draft.kind === 'DISABLE_ADMIN' ? 'btn-danger' : undefined}>
            Request approval
          </button>
        </>
      }
    >
      <form id="org-admin-request" onSubmit={submit} noValidate>
        {draft.kind === 'INVITE_ADMIN' && (
          <div className="form-grid">
            <label>
              Full name
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={200} />
            </label>
            <label>
              Work email
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={320} />
              {record.profile && <span className="hint">Allowed: {record.profile.allowedEmailDomains.map((d) => '@' + d).join(', ')}</span>}
            </label>
          </div>
        )}
        {draft.kind === 'DISABLE_ADMIN' && (
          <Callout tone="warning">Once approved, this admin is signed out and cannot sign in. Invite a replacement first if they are the only admin.</Callout>
        )}
        {draft.kind === 'RESET_ADMIN_MFA' && <Callout>Once approved, the admin sets up a new authenticator at the next sign-in.</Callout>}
        {draft.kind === 'UPDATE_PROFILE' && <OrganisationProfileForm value={profile} onChange={setProfile} />}
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

/** The organisation record and its Organisation Admins, managed by Garuda (never the organisation's other users). */
export function OrganisationRecordPanel({ tenantId }: { tenantId: string }) {
  const [draft, setDraft] = useState<Draft | null>(null);
  const record = useQuery({
    queryKey: ['org-record', tenantId],
    queryFn: () => api.get<OrganisationRecord>(`/api/v1/staff/organisations/${tenantId}/record`),
  });
  if (record.isPending) return <Loading />;
  if (record.isError || !record.data) return <ErrorText>The organisation record could not be loaded.</ErrorText>;
  const r = record.data;
  return (
    <div className="grid grid-main-side">
      <div>
        <Card
          title="Organisation Admins"
          flush
          actions={
            <button type="button" className="btn-primary btn-sm" onClick={() => setDraft({ kind: 'INVITE_ADMIN' })}>
              <Icon name="userPlus" size={15} />
              Invite admin
            </button>
          }
        >
          {r.admins.length === 0 ? (
            <EmptyState icon="shield" title="No Organisation Admin" />
          ) : (
            <ul className="list">
              {r.admins.map((a) => (
                <li key={a.id}>
                  <Avatar name={a.displayName} size="sm" />
                  <div className="grow">
                    <div className="title">{a.displayName}</div>
                    <span className="small">{a.email}</span>
                  </div>
                  {a.status === 'ACTIVE' ? (
                    <Badge tone="success">Active</Badge>
                  ) : a.status === 'INVITED' ? (
                    <Badge tone="warning">Invited</Badge>
                  ) : (
                    <Badge tone="neutral">Disabled</Badge>
                  )}
                  {a.mfaEnrolled && <Badge tone="primary">Authenticator</Badge>}
                  <ActionMenu label={`Actions for ${a.displayName}`}>
                    {(close) => (
                      <>
                        {a.status === 'INVITED' && (
                          <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                              close();
                              setDraft({ kind: 'INVITE_ADMIN' });
                            }}
                          >
                            <Icon name="refresh" size={16} />
                            Send a new invitation
                          </button>
                        )}
                        {a.status === 'ACTIVE' && a.mfaEnrolled && (
                          <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                              close();
                              setDraft({ kind: 'RESET_ADMIN_MFA', admin: a });
                            }}
                          >
                            <Icon name="refresh" size={16} />
                            Reset authenticator
                          </button>
                        )}
                        {a.status === 'ACTIVE' && (
                          <button
                            type="button"
                            role="menuitem"
                            className="danger"
                            onClick={() => {
                              close();
                              setDraft({ kind: 'DISABLE_ADMIN', admin: a });
                            }}
                          >
                            <Icon name="lock" size={16} />
                            Disable
                          </button>
                        )}
                        {a.status === 'DISABLED' && (
                          <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                              close();
                              setDraft({ kind: 'REACTIVATE_ADMIN', admin: a });
                            }}
                          >
                            <Icon name="play" size={16} />
                            Reactivate
                          </button>
                        )}
                      </>
                    )}
                  </ActionMenu>
                </li>
              ))}
            </ul>
          )}
          <div className="card-body small">
            Garuda manages Organisation Admins only. The organisation&apos;s other users are managed by its admins.
          </div>
        </Card>
        <Card title="Requests for this organisation" flush>
          <OrgAdminRequestTable requests={r.requests} />
        </Card>
      </div>
      <Card
        title="Organisation record"
        actions={
          <button type="button" className="btn-secondary btn-sm" onClick={() => setDraft({ kind: 'UPDATE_PROFILE' })}>
            Request change
          </button>
        }
      >
        {r.profile ? (
          <>
            <OrganisationProfileView profile={r.profile} />
            <p className="small" style={{ marginTop: '0.75rem' }}>
              Updated {formatDateTime(r.updatedAt)}
            </p>
          </>
        ) : (
          <Callout tone="warning">No record yet. Request a change to add it.</Callout>
        )}
      </Card>
      {draft && <RequestModal record={r} draft={draft} onClose={() => setDraft(null)} />}
    </div>
  );
}
