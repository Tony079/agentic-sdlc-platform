import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { OrganisationSummary, TenantLifecycleRequest } from '../api/types';
import { Badge, Callout, Card, CopyField, PageHeader, formatDateTime } from '../ui/components';

/**
 * Organisation settings for Organisation Admins: the sign-in address, email delivery status and, as the last resort,
 * a deletion request (O-25: a soft deletion that two authorised Garuda staff members must verify and approve).
 */
export function OrganisationPage() {
  const queryClient = useQueryClient();
  const summary = useQuery({ queryKey: ['organisation'], queryFn: () => api.get<OrganisationSummary>('/api/v1/organisation') });
  const requests = useQuery({
    queryKey: ['tenant-deletion-requests'],
    queryFn: () => api.get<TenantLifecycleRequest[]>('/api/v1/tenant/deletion-requests'),
  });
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    if (!window.confirm('Request deletion of your organisation? Access ends for everyone once Garuda approves it.')) return;
    try {
      await api.post('/api/v1/tenant/deletion-requests', { reason: reason.trim() });
      setReason('');
      setMessage({ tone: 'success', text: 'Your request was recorded. Two authorised staff members must verify and approve it.' });
      await queryClient.invalidateQueries({ queryKey: ['tenant-deletion-requests'] });
    } catch (e) {
      setMessage({
        tone: 'danger',
        text:
          e instanceof ApiError && e.code === 'LIFECYCLE_REQUEST_OPEN'
            ? 'A deletion request is already open.'
            : 'The request could not be recorded.',
      });
    }
  }

  const s = summary.data;
  return (
    <>
      <PageHeader eyebrow="Administration" title="Organisation" description="Settings that apply to your whole organisation." />
      <div className="grid grid-2">
        <Card title="Profile">
          <dl className="details">
            <dt>Name</dt>
            <dd>{s?.name ?? '—'}</dd>
            <dt>Sign-in code</dt>
            <dd>
              <code>{s?.slug ?? '—'}</code>
            </dd>
            <dt>People</dt>
            <dd>
              {s ? `${s.activeUsers} active, ${s.invitedUsers} invited, ${s.organisationAdmins} Organisation Admins` : '—'}
            </dd>
          </dl>
          {s && (
            <label style={{ marginTop: '1rem' }}>
              Sign-in address
              <CopyField value={`${window.location.origin}/o/${s.slug}/login`} label="Sign-in address" />
            </label>
          )}
        </Card>
        <Card title="Security">
          <dl className="details">
            <dt>Admin sign-in</dt>
            <dd>
              <Badge tone="success">Password and authenticator</Badge>
            </dd>
            <dt>Member sign-in</dt>
            <dd>Password (at least 12 characters)</dd>
            <dt>Invitation email</dt>
            <dd>
              {s?.emailConfigured ? (
                <Badge tone="success">Sent by Garuda</Badge>
              ) : (
                <Badge tone="warning">Copy-link only</Badge>
              )}
            </dd>
            <dt>Data isolation</dt>
            <dd>Enforced by the database for every request</dd>
          </dl>
        </Card>
      </div>
      <Card title="Delete organisation">
        <p className="small">
          Deletion is a soft deletion: access stops for everyone in your organisation, and data is kept under the agreed
          retention rules. It takes effect only after two authorised Garuda staff members approve it.
        </p>
        <form onSubmit={(e) => void submit(e)} style={{ maxWidth: 640 }}>
          <label>
            Reason (at least 10 characters)
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={2000} />
          </label>
          <button type="submit" className="btn-danger" disabled={reason.trim().length < 10}>
            Request deletion
          </button>
        </form>
        {message && (
          <div style={{ marginTop: '1rem' }}>
            <Callout tone={message.tone}>{message.text}</Callout>
          </div>
        )}
        {requests.data && requests.data.length > 0 && (
          <>
            <h3 style={{ marginTop: '1.25rem' }}>Requests</h3>
            <ul className="list">
              {requests.data.map((r) => (
                <li key={r.id} style={{ paddingLeft: 0 }}>
                  <Badge tone={r.status === 'EXECUTED' ? 'danger' : r.status === 'REJECTED' ? 'neutral' : 'warning'}>
                    {r.status}
                  </Badge>
                  <span>{r.kind}</span>
                  <span className="small">{formatDateTime(r.requestedAt)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </Card>
    </>
  );
}
