import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { OnboardingApproved, OnboardingRequest, OrganisationStatusRequest } from '../../api/types';
import { useStaffMe } from '../../components/StaffLayout';
import { Icon } from '../../ui/Icon';
import { useToast } from '../../ui/Toast';
import {
  Badge,
  Callout,
  Card,
  CopyField,
  EmptyState,
  ErrorText,
  Loading,
  Modal,
  PageHeader,
  Tabs,
  formatDateTime,
  relativeTime,
  type Tone,
} from '../../ui/components';

const STATUS_TONE: Record<string, Tone> = { REQUESTED: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'neutral' };
const STATUS_LABEL: Record<string, string> = {
  REQUESTED: 'Awaiting approval',
  EXECUTED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

function slugify(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 40);
}

const ERRORS: Record<string, string> = {
  SLUG_TAKEN: 'Another organisation already uses this sign-in code.',
  NAME_TAKEN: 'Another organisation already has this name.',
  SECOND_APPROVER_REQUIRED: 'A different Garuda administrator must approve this request.',
  REQUEST_STATE: 'This request has already been decided.',
};

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return ERRORS[error.code] ?? (error.status === 422 ? error.message : fallback);
  return fallback;
}

function RequestModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [adminName, setAdminName] = useState('');
  const [adminEmail, setAdminEmail] = useState('');
  const create = useMutation({
    mutationFn: () =>
      api.post<OnboardingRequest>('/api/v1/staff/organisation-requests', {
        organisationName: name.trim(),
        slug: slug.trim(),
        adminEmail: adminEmail.trim(),
        adminDisplayName: adminName.trim(),
      }),
    onSuccess: async () => {
      toast.success('Onboarding requested. A second administrator must approve it.');
      await queryClient.invalidateQueries({ queryKey: ['staff-onboarding'] });
      onClose();
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }
  return (
    <Modal
      wide
      title="Onboard an organisation"
      description="A second Garuda administrator approves the request. On approval the organisation is created and its first Organisation Admin is invited."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" form="onboard-form" disabled={create.isPending || !name.trim() || !slug.trim() || !adminEmail.trim() || !adminName.trim()}>
            Request onboarding
          </button>
        </>
      }
    >
      <form id="onboard-form" onSubmit={submit} noValidate>
        <h3>Organisation</h3>
        <div className="form-grid">
          <label>
            Legal or trading name
            <input
              value={name}
              maxLength={200}
              onChange={(e) => {
                setName(e.target.value);
                if (!slugEdited) setSlug(slugify(e.target.value));
              }}
              required
            />
          </label>
          <label>
            Sign-in code
            <input
              value={slug}
              maxLength={63}
              onChange={(e) => {
                setSlugEdited(true);
                setSlug(e.target.value.toLowerCase());
              }}
              className="mono"
              required
            />
            <span className="hint">
              Sign-in address: {window.location.origin}/o/{slug || 'code'}/login
            </span>
          </label>
        </div>
        <h3>First Organisation Admin</h3>
        <div className="form-grid">
          <label>
            Full name
            <input value={adminName} maxLength={200} onChange={(e) => setAdminName(e.target.value)} required />
          </label>
          <label>
            Work email
            <input type="email" value={adminEmail} maxLength={320} onChange={(e) => setAdminEmail(e.target.value)} required />
          </label>
        </div>
        {create.isError && <ErrorText>{message(create.error, 'The request could not be recorded.')}</ErrorText>}
      </form>
    </Modal>
  );
}

function ApprovedModal({ result, onClose }: { result: OnboardingApproved; onClose: () => void }) {
  return (
    <Modal
      title={`${result.request.organisationName} is live`}
      onClose={onClose}
      footer={
        <>
          {result.request.createdTenantId && (
            <Link to={`/staff/organisations/${result.request.createdTenantId}`} className="btn btn-secondary">
              Open organisation
            </Link>
          )}
          <button type="button" className="btn-primary" onClick={onClose}>
            Done
          </button>
        </>
      }
    >
      {result.emailStatus === 'SENT' ? (
        <Callout tone="success">
          We emailed an invitation to {result.request.adminDisplayName} ({result.request.adminEmail}).
        </Callout>
      ) : (
        <Callout tone="warning">
          {result.emailStatus === 'FAILED' ? 'The invitation email could not be delivered.' : 'Email is not set up.'} Send this
          link to {result.request.adminDisplayName} ({result.request.adminEmail}) yourself.
        </Callout>
      )}
      <label>
        Invitation link for the first Organisation Admin
        <CopyField value={result.invitationLink} label="Invitation link" />
        <span className="hint">Works once, expires in 72 hours. Share it only with the named person.</span>
      </label>
    </Modal>
  );
}

/** Two-person onboarding, suspension and reactivation of client organisations (FR-ORG-01..03, GD-03). */
export function StaffOnboardingPage() {
  const me = useStaffMe();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [tab, setTab] = useState<'onboarding' | 'status'>('onboarding');
  const [requesting, setRequesting] = useState(false);
  const [approved, setApproved] = useState<OnboardingApproved | null>(null);
  const onboarding = useQuery({
    queryKey: ['staff-onboarding'],
    queryFn: () => api.get<OnboardingRequest[]>('/api/v1/staff/organisation-requests'),
  });
  const statusRequests = useQuery({
    queryKey: ['staff-status-requests'],
    queryFn: () => api.get<OrganisationStatusRequest[]>('/api/v1/staff/organisation-status-requests'),
  });
  const myId = me.data?.staffId;

  async function approve(request: OnboardingRequest) {
    if (!window.confirm(`Create ${request.organisationName} and invite ${request.adminEmail} as its first Organisation Admin?`)) return;
    try {
      const result = await api.post<OnboardingApproved>(`/api/v1/staff/organisation-requests/${request.id}/approve`);
      setApproved(result);
      await queryClient.invalidateQueries({ queryKey: ['staff-onboarding'] });
      await queryClient.invalidateQueries({ queryKey: ['staff-dashboard'] });
    } catch (e) {
      toast.error(message(e, 'The organisation could not be created.'));
    }
  }

  async function decide(path: string, success: string, key: string) {
    try {
      await api.post(path, {});
      toast.success(success);
      await queryClient.invalidateQueries({ queryKey: [key] });
      await queryClient.invalidateQueries({ queryKey: ['staff-dashboard'] });
    } catch (e) {
      toast.error(message(e, 'The decision could not be recorded.'));
    }
  }

  const open = (onboarding.data ?? []).filter((r) => r.status === 'REQUESTED');
  const openStatus = (statusRequests.data ?? []).filter((r) => r.status === 'REQUESTED');
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Onboarding & status"
        description="Every new organisation, suspension and reactivation needs two Garuda administrators: one requests, another approves."
        actions={
          <button type="button" className="btn-primary" onClick={() => setRequesting(true)}>
            <Icon name="plus" size={16} />
            Onboard organisation
          </button>
        }
      />
      <Tabs
        label="Request types"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'onboarding', label: 'Onboarding', count: open.length },
          { id: 'status', label: 'Suspension & reactivation', count: openStatus.length },
        ]}
      />
      {tab === 'onboarding' && (
        <Card title="Onboarding requests" flush>
          {onboarding.isPending && <div className="card-body"><Loading /></div>}
          {onboarding.isError && <div className="card-body"><ErrorText>Requests could not be loaded.</ErrorText></div>}
          {onboarding.data && onboarding.data.length === 0 && (
            <EmptyState icon="building" title="No onboarding requests" action={<button type="button" className="btn-primary" onClick={() => setRequesting(true)}>Onboard an organisation</button>}>
              Start by requesting a new organisation with its first Organisation Admin.
            </EmptyState>
          )}
          {onboarding.data && onboarding.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Organisation</th>
                    <th scope="col">First admin</th>
                    <th scope="col">Requested</th>
                    <th scope="col">Status</th>
                    <th scope="col" className="num">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {onboarding.data.map((r) => {
                    const mine = r.requestedBy === myId;
                    return (
                      <tr key={r.id}>
                        <td>
                          <span className="primary-cell">{r.organisationName}</span>
                          <span className="sub mono">{r.organisationSlug}</span>
                        </td>
                        <td>
                          {r.adminDisplayName}
                          <span className="sub">{r.adminEmail}</span>
                        </td>
                        <td>
                          {r.requestedByName}
                          {mine && <span className="muted"> (you)</span>}
                          <span className="sub">{relativeTime(r.requestedAt)}</span>
                        </td>
                        <td>
                          <Badge tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</Badge>
                          {r.status === 'EXECUTED' && (
                            <span className="sub">
                              by {r.approvedByName} · {formatDateTime(r.executedAt)}
                            </span>
                          )}
                        </td>
                        <td className="num">
                          {r.status === 'REQUESTED' && (
                            <div className="row" style={{ justifyContent: 'flex-end' }}>
                              {mine ? (
                                <>
                                  <span className="small">Needs a second approver</span>
                                  <button
                                    type="button"
                                    className="btn-ghost btn-sm"
                                    onClick={() => void decide(`/api/v1/staff/organisation-requests/${r.id}/cancel`, 'Request cancelled.', 'staff-onboarding')}
                                  >
                                    Cancel
                                  </button>
                                </>
                              ) : (
                                <>
                                  <button
                                    type="button"
                                    className="btn-secondary btn-sm"
                                    onClick={() => void decide(`/api/v1/staff/organisation-requests/${r.id}/reject`, 'Request rejected.', 'staff-onboarding')}
                                  >
                                    Reject
                                  </button>
                                  <button type="button" className="btn-primary btn-sm" onClick={() => void approve(r)}>
                                    <Icon name="check" size={15} />
                                    Approve
                                  </button>
                                </>
                              )}
                            </div>
                          )}
                          {r.status === 'EXECUTED' && r.createdTenantId && (
                            <Link to={`/staff/organisations/${r.createdTenantId}`} className="btn btn-ghost btn-sm">
                              Open
                            </Link>
                          )}
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
      {tab === 'status' && (
        <Card title="Suspension and reactivation requests" flush>
          {statusRequests.isPending && <div className="card-body"><Loading /></div>}
          {statusRequests.data && statusRequests.data.length === 0 && (
            <EmptyState icon="pause" title="No requests">
              Suspend or reactivate an organisation from its page under Organisations.
            </EmptyState>
          )}
          {statusRequests.data && statusRequests.data.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Organisation</th>
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
                  {statusRequests.data.map((r) => {
                    const mine = r.requestedBy === myId;
                    return (
                      <tr key={r.id}>
                        <td>
                          <Link to={`/staff/organisations/${r.tenantId}`}>{r.organisationName}</Link>
                        </td>
                        <td>{r.kind === 'SUSPEND' ? <Badge tone="danger">Suspend</Badge> : <Badge tone="success">Reactivate</Badge>}</td>
                        <td className="small" style={{ maxWidth: 320 }}>
                          {r.reason}
                        </td>
                        <td>
                          {r.requestedByName}
                          {mine && <span className="muted"> (you)</span>}
                          <span className="sub">{relativeTime(r.requestedAt)}</span>
                        </td>
                        <td>
                          <Badge tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</Badge>
                        </td>
                        <td className="num">
                          {r.status === 'REQUESTED' &&
                            (mine ? (
                              <button
                                type="button"
                                className="btn-ghost btn-sm"
                                onClick={() => void decide(`/api/v1/staff/organisation-status-requests/${r.id}/cancel`, 'Request cancelled.', 'staff-status-requests')}
                              >
                                Cancel
                              </button>
                            ) : (
                              <div className="row" style={{ justifyContent: 'flex-end' }}>
                                <button
                                  type="button"
                                  className="btn-secondary btn-sm"
                                  onClick={() => void decide(`/api/v1/staff/organisation-status-requests/${r.id}/reject`, 'Request rejected.', 'staff-status-requests')}
                                >
                                  Reject
                                </button>
                                <button
                                  type="button"
                                  className={r.kind === 'SUSPEND' ? 'btn-danger btn-sm' : 'btn-primary btn-sm'}
                                  onClick={() => {
                                    if (window.confirm(`${r.kind === 'SUSPEND' ? 'Suspend' : 'Reactivate'} ${r.organisationName}?`)) {
                                      void decide(
                                        `/api/v1/staff/organisation-status-requests/${r.id}/approve`,
                                        r.kind === 'SUSPEND' ? 'Organisation suspended.' : 'Organisation reactivated.',
                                        'staff-status-requests',
                                      );
                                    }
                                  }}
                                >
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
      {requesting && <RequestModal onClose={() => setRequesting(false)} />}
      {approved && <ApprovedModal result={approved} onClose={() => setApproved(null)} />}
    </>
  );
}
