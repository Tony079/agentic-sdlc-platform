import { useState, type ReactNode } from 'react';
import { Link, NavLink, Navigate, Outlet, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { StaffMe } from '../api/types';
import { GarudaMark, Icon, type IconName } from '../ui/Icon';
import { Loading } from '../ui/components';
import { ThemeToggle, UserMenu } from './Layout';

export function useStaffMe() {
  return useQuery({
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
}

export function hasAny(me: StaffMe | null | undefined, ...capabilities: string[]): boolean {
  return !!me && capabilities.some((c) => me.capabilities.includes(c));
}

function Item({ to, icon, children, end }: { to: string; icon: IconName; children: ReactNode; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
      <Icon name={icon} />
      <span>{children}</span>
    </NavLink>
  );
}

/** Garuda staff console shell. Navigation follows the staff member's capabilities; the server re-checks each call. */
export function StaffLayout() {
  const me = useStaffMe();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [navOpen, setNavOpen] = useState(false);

  if (me.isPending) return <Loading />;
  if (!me.data) return <Navigate to="/staff/login" replace />;
  const staff = me.data;
  const oversight = hasAny(staff, 'ORG_ONBOARD', 'STAFF_ADMIN', 'AUDITOR');

  async function signOut() {
    try {
      await api.post('/api/v1/staff/auth/logout');
    } finally {
      queryClient.clear();
      navigate('/staff/login', { replace: true });
    }
  }

  return (
    <div className={`app${navOpen ? ' nav-open' : ''}`}>
      <aside
        className="sidebar"
        aria-label="Sidebar"
        onClick={(e) => {
          if ((e.target as HTMLElement).closest('a')) setNavOpen(false);
        }}
      >
        <Link to="/staff" className="brand">
          <GarudaMark />
          <span>
            GARUDA
            <small>Operations console</small>
          </span>
        </Link>
        <div className="org-chip">
          <span className="stat-icon" style={{ background: 'rgba(255,255,255,0.08)', color: '#fff' }}>
            <Icon name="building" size={15} />
          </span>
          <div>
            <div className="org-name">{staff.activeTenant ? staff.activeTenant.name : 'No active organisation'}</div>
            <div className="org-meta">Active organisation</div>
          </div>
        </div>
        <nav aria-label="Console">
          {oversight && (
            <>
              <div className="nav-section">Platform</div>
              <Item to="/staff" icon="chart" end>
                Dashboard
              </Item>
              <Item to="/staff/organisations" icon="building">
                Organisations
              </Item>
            </>
          )}
          {hasAny(staff, 'ORG_ONBOARD') && (
            <Item to="/staff/onboarding" icon="checkCircle">
              Onboarding &amp; status
            </Item>
          )}
          <div className="nav-section">Delivery</div>
          <Item to="/staff/workspace" icon="layers" end={!oversight}>
            Workspace
          </Item>
          <Item to="/staff/inbox" icon="inbox">
            Reviewer inbox
          </Item>
          <Item to="/staff/operations" icon="settings">
            Operations
          </Item>
        </nav>
        <div className="sidebar-footer small" style={{ color: 'var(--sidebar-text)' }}>
          Capabilities: {staff.capabilities.length ? staff.capabilities.length : 'none'}
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button type="button" className="btn-ghost btn-icon menu-toggle" aria-label="Open navigation" onClick={() => setNavOpen((o) => !o)}>
            <Icon name="menu" />
          </button>
          <span className="badge tone-primary no-dot">Staff</span>
          <div className="spacer" />
          <ThemeToggle />
          <UserMenu name={staff.displayName} detail="Garuda staff" email={staff.email} onSignOut={() => void signOut()} />
        </header>
        <main className="page" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** /staff: the dashboard for oversight roles, the delivery workspace for everyone else. */
export function StaffIndex({ dashboard, workspace }: { dashboard: ReactNode; workspace: ReactNode }) {
  const me = useStaffMe();
  if (me.isPending) return <Loading />;
  return <>{hasAny(me.data, 'ORG_ONBOARD', 'STAFF_ADMIN', 'AUDITOR') ? dashboard : workspace}</>;
}
