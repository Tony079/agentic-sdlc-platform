import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { TenantLifecycleRequest } from '../api/types';

/**
 * O-25: a Client Approver may request deletion of the organisation. It is a soft deletion that two authorised staff
 * members must verify and approve; nothing happens on submission.
 */
export function OrganisationPage() {
  const queryClient = useQueryClient();
  const requests = useQuery({
    queryKey: ['tenant-deletion-requests'],
    queryFn: () => api.get<TenantLifecycleRequest[]>('/api/v1/tenant/deletion-requests'),
  });
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    try {
      await api.post('/api/v1/tenant/deletion-requests', { reason: reason.trim() });
      setReason('');
      setMessage('Your request was recorded. Two authorised staff members must verify and approve it.');
      await queryClient.invalidateQueries({ queryKey: ['tenant-deletion-requests'] });
    } catch (e) {
      setMessage(
        e instanceof ApiError && e.code === 'LIFECYCLE_REQUEST_OPEN'
          ? 'A deletion request is already open.'
          : 'The request could not be recorded.',
      );
    }
  }

  return (
    <section>
      <h1>Organisation</h1>
      <h2>Request deletion</h2>
      <p className="small">
        Deletion is a soft deletion: access stops for everyone in your organisation, and data is kept under the agreed
        retention rules. It takes effect only after two authorised staff members approve it.
      </p>
      <form onSubmit={(e) => void submit(e)}>
        <label>
          Reason (at least 10 characters)
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={2000} />
        </label>
        <button type="submit" disabled={reason.trim().length < 10}>
          Request deletion
        </button>
      </form>
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      <h2>Requests</h2>
      {requests.data?.length === 0 && <p>No requests.</p>}
      <ul>
        {requests.data?.map((r) => (
          <li key={r.id}>
            {r.kind} · {r.status} · {new Date(r.requestedAt).toLocaleString()}
          </li>
        ))}
      </ul>
    </section>
  );
}
