import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { StaffIndex, StaffLayout } from './components/StaffLayout';
import { Loading } from './ui/components';
import { AccountLinkPage, ForgotPasswordPage } from './pages/AccountLinkPage';
import { ChangeDecisionsPage } from './pages/ChangeDecisionsPage';
import { ClarificationsPage } from './pages/ClarificationsPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrganisationPage } from './pages/OrganisationPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RedeemLinkPage } from './pages/RedeemLinkPage';
import { RequirementDetailPage } from './pages/RequirementDetailPage';
import { RequirementFormPage } from './pages/RequirementFormPage';
import { RequirementsPage } from './pages/RequirementsPage';
import { UsersPage } from './pages/UsersPage';
import { StaffClarificationsPage } from './pages/staff/StaffClarificationsPage';
import { StaffDashboardPage } from './pages/staff/StaffDashboardPage';
import { StaffHomePage } from './pages/staff/StaffHomePage';
import { StaffLoginPage } from './pages/staff/StaffLoginPage';
import { StaffOnboardingPage } from './pages/staff/StaffOnboardingPage';
import { StaffOperationsPage } from './pages/staff/StaffOperationsPage';
import { StaffOrganisationDetailPage, StaffOrganisationsPage } from './pages/staff/StaffOrganisationsPage';
import { StaffReviewPage } from './pages/staff/StaffReviewPage';
import { StaffTeamPage } from './pages/staff/StaffTeamPage';

/** Redirects to sign-in when there is no session. A convenience only: the API enforces authentication. */
function RequireSession({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth();
  if (loading) {
    return <Loading />;
  }
  return me ? <>{children}</> : <Navigate to="/login" replace />;
}

/** Administration screens; the server refuses every call for anyone but an Organisation Admin. */
function RequireOrgAdmin({ children }: { children: ReactNode }) {
  const { isOrgAdmin } = useAuth();
  return isOrgAdmin ? <>{children}</> : <NotFoundPage />;
}

export function App() {
  return (
    <Routes>
      {/* Organisation sign-in and account links (Garuda FR-ORG-07/08). */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/o/:slug/login" element={<LoginPage />} />
      <Route path="/o/:slug" element={<LoginPage />} />
      <Route path="/o/:slug/invite" element={<AccountLinkPage purpose="INVITE" />} />
      <Route path="/o/:slug/reset" element={<AccountLinkPage purpose="PASSWORD_RESET" />} />
      <Route path="/o/:slug/forgot" element={<ForgotPasswordPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />

      {/* Garuda staff console (separate login with TOTP; never a client role). */}
      <Route path="/staff/login" element={<StaffLoginPage />} />
      <Route path="/staff/invite" element={<AccountLinkPage purpose="INVITE" staff />} />
      <Route path="/staff/reset" element={<AccountLinkPage purpose="PASSWORD_RESET" staff />} />
      <Route path="/staff" element={<StaffLayout />}>
        <Route index element={<StaffIndex dashboard={<StaffDashboardPage />} workspace={<StaffHomePage />} />} />
        <Route path="workspace" element={<StaffHomePage />} />
        <Route path="organisations" element={<StaffOrganisationsPage />} />
        <Route path="organisations/:id" element={<StaffOrganisationDetailPage />} />
        <Route path="onboarding" element={<StaffOnboardingPage />} />
        <Route path="team" element={<StaffTeamPage />} />
        <Route path="reviews/:versionId" element={<StaffReviewPage />} />
        <Route path="inbox" element={<StaffClarificationsPage />} />
        <Route path="operations" element={<StaffOperationsPage />} />
      </Route>

      <Route
        element={
          <RequireSession>
            <Layout />
          </RequireSession>
        }
      >
        <Route path="/" element={<HomePage />} />
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/projects/:id" element={<ProjectDetailPage />} />
        <Route path="/requirements" element={<RequirementsPage />} />
        <Route path="/requirements/new" element={<RequirementFormPage />} />
        <Route path="/requirements/:id" element={<RequirementDetailPage />} />
        <Route path="/requirements/:id/edit" element={<RequirementFormPage />} />
        <Route path="/repository-links/redeem" element={<RedeemLinkPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/clarifications" element={<ClarificationsPage />} />
        <Route path="/change-requests" element={<ChangeDecisionsPage />} />
        <Route
          path="/admin/users"
          element={
            <RequireOrgAdmin>
              <UsersPage />
            </RequireOrgAdmin>
          }
        />
        <Route
          path="/organisation"
          element={
            <RequireOrgAdmin>
              <OrganisationPage />
            </RequireOrgAdmin>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
