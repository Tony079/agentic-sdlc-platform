import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { NotificationItem, ProjectList } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { GarudaMark, Icon, type IconName } from '../ui/Icon';
import { Avatar, ROLE_LABELS } from '../ui/components';

function NavItem({ to, icon, children, count, end }: { to: string; icon: IconName; children: ReactNode; count?: number; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
      <Icon name={icon} />
      <span>{children}</span>
      {count !== undefined && count > 0 && (
        <span className="count" aria-label={`${count} unread`}>
          {count > 99 ? '99+' : count}
        </span>
      )}
    </NavLink>
  );
}

/** Switches between light, dark and the system theme; the choice is kept on this device only. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<string>(() => {
    try {
      return localStorage.getItem('garuda-theme') ?? 'system';
    } catch {
      return 'system';
    }
  });
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('garuda-theme', theme);
    } catch {
      /* storage unavailable: the choice lasts for this page only */
    }
  }, [theme]);
  const dark =
    theme === 'dark' || (theme === 'system' && typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches);
  return (
    <button
      type="button"
      className="btn-ghost btn-icon"
      aria-label={dark ? 'Use light theme' : 'Use dark theme'}
      title={dark ? 'Light theme' : 'Dark theme'}
      onClick={() => setTheme(dark ? 'light' : 'dark')}
    >
      <Icon name={dark ? 'sun' : 'moon'} />
    </button>
  );
}

export function UserMenu({
  name,
  detail,
  email,
  onSignOut,
}: {
  name: string;
  detail: string;
  email: string;
  onSignOut: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);
  return (
    <div className="user-menu" ref={ref}>
      <button type="button" className="user-button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Avatar name={name} />
        <span className="who">
          <strong>{name}</strong>
          <span>{detail}</span>
        </span>
        <Icon name="chevronDown" size={16} />
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="menu-header">
            <strong>{name}</strong>
            <div className="small">{email}</div>
          </div>
          <button type="button" role="menuitem" className="danger" onClick={onSignOut}>
            <Icon name="logout" size={16} />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

/** Client application shell. Navigation follows the user's roles for convenience; the server enforces every rule. */
export function Layout() {
  const { me, isOrgAdmin, logout } = useAuth();
  const navigate = useNavigate();
  const [navOpen, setNavOpen] = useState(false);

  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.get<NotificationItem[]>('/api/v1/notifications'),
    enabled: !!me,
    refetchInterval: 60_000,
  });
  const projects = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.get<ProjectList>('/api/v1/projects'),
    enabled: !!me,
  });
  const unread = Array.isArray(notifications.data) ? notifications.data.filter((n) => !n.readAt).length : 0;
  const approver = !!projects.data?.items.some((p) => p.myRole === 'APPROVER');

  async function handleLogout() {
    try {
      await logout();
    } finally {
      navigate('/login', { replace: true });
    }
  }

  const role = me?.roles[0] ?? 'MEMBER';
  return (
    <div className={`app${navOpen ? ' nav-open' : ''}`}>
      <aside
        className="sidebar"
        aria-label="Sidebar"
        onClick={(e) => {
          if ((e.target as HTMLElement).closest('a')) setNavOpen(false);
        }}
      >
        <Link to="/" className="brand">
          <GarudaMark />
          <span>
            GARUDA
            <small>Requirements to delivery</small>
          </span>
        </Link>
        {me && (
          <div className="org-chip">
            <Avatar name={me.tenant.name} size="sm" square />
            <div>
              <div className="org-name">{me.tenant.name}</div>
              <div className="org-meta">{ROLE_LABELS[role] ?? role}</div>
            </div>
          </div>
        )}
        <nav aria-label="Main">
          <NavItem to="/" icon="home" end>
            Home
          </NavItem>
          <div className="nav-section">Work</div>
          <NavItem to="/projects" icon="folder">
            Projects
          </NavItem>
          <NavItem to="/requirements" icon="file">
            Requirements
          </NavItem>
          <NavItem to="/clarifications" icon="chat">
            Clarifications
          </NavItem>
          <NavItem to="/notifications" icon="bell" count={unread}>
            Notifications
          </NavItem>
          {(approver || isOrgAdmin) && (
            <>
              <div className="nav-section">Approvals</div>
              <NavItem to="/change-requests" icon="checkCircle">
                Change decisions
              </NavItem>
              <NavItem to="/repository-links/redeem" icon="git">
                Link a repository
              </NavItem>
            </>
          )}
          {isOrgAdmin && (
            <>
              <div className="nav-section">Administration</div>
              <NavItem to="/admin/users" icon="users">
                Users
              </NavItem>
              <NavItem to="/organisation" icon="building">
                Organisation
              </NavItem>
            </>
          )}
        </nav>
        <div className="sidebar-footer small">
          <span style={{ color: 'var(--sidebar-text)' }}>© Garuda</span>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button
            type="button"
            className="btn-ghost btn-icon menu-toggle"
            aria-label="Open navigation"
            onClick={() => setNavOpen((o) => !o)}
          >
            <Icon name="menu" />
          </button>
          <div className="spacer" />
          <ThemeToggle />
          {me && (
            <UserMenu name={me.displayName} detail={me.tenant.name} email={me.email} onSignOut={() => void handleLogout()} />
          )}
        </header>
        <main className="page" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
