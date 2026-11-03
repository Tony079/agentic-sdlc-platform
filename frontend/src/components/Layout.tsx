import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** App shell. Navigation reflects roles for convenience only; the server enforces every permission. */
export function Layout() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();

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
          <NavLink to="/projects">Projects</NavLink> <NavLink to="/requirements">Requirements</NavLink>
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
      <main>
        <Outlet />
      </main>
    </div>
  );
}
