import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { NotificationItem } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DevelopmentAdaptersBanner } from './MockNotice';

/** App shell. Navigation reflects roles for convenience only; the server enforces every permission. */
export function Layout() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.get<NotificationItem[]>('/api/v1/notifications'),
    enabled: !!me,
    refetchInterval: 60_000,
  });
  const unread = Array.isArray(notifications.data) ? notifications.data.filter((n) => !n.readAt).length : 0;

  async function handleLogout() {
    try {
      await logout();
    } finally {
      navigate('/login', { replace: true });
    }
  }

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Agentic SDLC Platform</span>
        <nav aria-label="Main">
          <NavLink to="/" end>
            Dashboard
          </NavLink>{' '}
          <NavLink to="/projects">Projects</NavLink> <NavLink to="/requirements">Requirements</NavLink>{' '}
          <NavLink to="/clarifications">Clarifications</NavLink>{' '}
          <NavLink to="/notifications">Notifications{unread > 0 ? ` (${unread})` : ''}</NavLink>
          {me?.roles.includes('CLIENT_APPROVER') && (
            <>
              {' '}
              <NavLink to="/repository-links/redeem">Link a repository</NavLink>{' '}
              <NavLink to="/change-requests">Change decisions</NavLink>{' '}
              <NavLink to="/organisation">Organisation</NavLink>
            </>
          )}
        </nav>
        {me && (
          <div className="session">
            <span>
              {me.displayName} · {me.tenant.name}
            </span>
            <button type="button" onClick={handleLogout}>
              Sign out
            </button>
          </div>
        )}
      </header>
      {me && <DevelopmentAdaptersBanner />}
      <main>
        <Outlet />
      </main>
    </div>
  );
}
