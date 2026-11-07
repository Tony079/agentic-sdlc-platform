import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RequirementDetailPage } from './pages/RequirementDetailPage';
import { RequirementFormPage } from './pages/RequirementFormPage';
import { RequirementsPage } from './pages/RequirementsPage';
import { RedeemLinkPage } from './pages/RedeemLinkPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { StaffHomePage } from './pages/staff/StaffHomePage';
import { StaffLoginPage } from './pages/staff/StaffLoginPage';
import { StaffReviewPage } from './pages/staff/StaffReviewPage';

/** Redirects to sign-in when there is no session. A convenience only: the API enforces authentication. */
function RequireSession({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth();
  if (loading) {
    return <p>Loading…</p>;
  }
  return me ? <>{children}</> : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* Internal staff (separate login with TOTP; never a client role). */}
      <Route path="/staff/login" element={<StaffLoginPage />} />
      <Route path="/staff" element={<StaffHomePage />} />
      <Route path="/staff/reviews/:versionId" element={<StaffReviewPage />} />
      <Route
        element={
          <RequireSession>
            <Layout />
          </RequireSession>
        }
      >
        <Route path="/" element={<Navigate to="/projects" replace />} />
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/requirements" element={<RequirementsPage />} />
        <Route path="/requirements/new" element={<RequirementFormPage />} />
        <Route path="/requirements/:id" element={<RequirementDetailPage />} />
        <Route path="/requirements/:id/edit" element={<RequirementFormPage />} />
        <Route path="/repository-links/redeem" element={<RedeemLinkPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
