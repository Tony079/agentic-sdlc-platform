import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { PendingRepositoryLink, StaffMe } from '../../api/types';

const REGISTER_ERRORS: Record<string, string> = {
  REPOSITORY_ALREADY_REGISTERED: 'A repository is already registered for that requirement.',
  REGISTRATION_NOT_ALLOWED_IN_STATE: 'The requirement no longer accepts a repository.',
  FORBIDDEN: 'You are not allowed to register repositories for this organisation.',
};

/**
 * Staff workspace (O-06, C-01): choose an assigned organisation as the active tenant, then register repositories that
 * a Client Approver authorised. Shows no requirement content. The server re-checks every permission.
 */
export function StaffHomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const me = useQuery({
    queryKey: ['staff-me'],
    queryFn: async () => {
      try {
        return await api.get<StaffMe>('/api/v1/staff/me');
      } catch (e) {
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
          return null;
        }
        throw e;
      }
    },
    retry: false,
  });
  const canRegister = !!me.data?.activeTenant && me.data.capabilities.includes('REPOSITORY_REGISTER');
  const pending = useQuery({
    queryKey: ['staff-links', me.data?.activeTenant?.id],
    queryFn: () => api.get<PendingRepositoryLink[]>('/api/v1/staff/repository-links'),
    enabled: canRegister,
  });

  if (me.isPending) {
    return <p>Loading…</p>;
  }
  if (!me.data) {
    return <Navigate to="/staff/login" replace />;
  }
  const staff = me.data;

  async function selectTenant(tenantId: string) {
    setMessage(null);
    await api.put<StaffMe>('/api/v1/staff/session/active-tenant', { tenantId });
    await queryClient.invalidateQueries({ queryKey: ['staff-me'] });
  }

  async function register(link: PendingRepositoryLink) {
    setMessage(null);
    setBusy(true);
    try {
      await api.post(`/api/v1/projects/${link.projectId}/repositories`, { linkId: link.linkId });
      setMessage(`Registered ${link.repositoryUrl}. Verification with GitHub runs in the background.`);
      await queryClient.invalidateQueries({ queryKey: ['staff-links'] });
    } catch (e) {
      setMessage(e instanceof ApiError ? (REGISTER_ERRORS[e.code] ?? 'Registration failed.') : 'Registration failed.');
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    try {
      await api.post('/api/v1/staff/auth/logout');
    } finally {
      queryClient.clear();
      navigate('/staff/login', { replace: true });
    }
  }

  return (
    <section>
      <h1>Staff workspace</h1>
      <p>
        {staff.displayName} · <button type="button" onClick={() => void signOut()}>Sign out</button>
      </p>
      <label>
        Active organisation
        <select
          value={staff.activeTenant?.id ?? ''}
          onChange={(e) => e.target.value && void selectTenant(e.target.value)}
        >
          <option value="">Choose…</option>
          {staff.assignedTenants.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      {staff.assignedTenants.length === 0 && <p>You are not assigned to any organisation.</p>}
      {message && (
        <p className="notice" role="status">
          {message}
        </p>
      )}
      {canRegister && (
        <>
          <h2>Repositories awaiting registration</h2>
          {pending.data?.length === 0 && <p>Nothing is waiting.</p>}
          <ul>
            {pending.data?.map((link) => (
              <li key={link.linkId}>
                <code>{link.repositoryUrl}</code> · authorised {new Date(link.authorizedAt).toLocaleString()}{' '}
                <button type="button" disabled={busy} onClick={() => void register(link)}>
                  Register
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {staff.activeTenant && !staff.capabilities.includes('REPOSITORY_REGISTER') && (
        <p>You do not hold the repository registration capability.</p>
      )}
    </section>
  );
}
