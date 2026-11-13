import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { AccountLinkPage } from '../pages/AccountLinkPage';
import { UsersPage } from '../pages/UsersPage';
import { StaffDashboardPage } from '../pages/staff/StaffDashboardPage';
import { StaffOnboardingPage } from '../pages/staff/StaffOnboardingPage';
import { mockFetch, renderWithProviders } from './render';

const admin = {
  userId: 'u1',
  displayName: 'Aaron Admin',
  email: 'aaron@acme.test',
  tenant: { id: 't1', name: 'Acme' },
  roles: ['ORG_ADMIN'],
};

const TOKEN = 'A'.repeat(43);

describe('Organisation users', () => {
  it('invites a person into a project with a role and shows the copyable link', async () => {
    document.cookie = 'XSRF-TOKEN=g1; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 200, body: admin };
      if (url === '/api/v1/organisation') {
        return {
          status: 200,
          body: { id: 't1', name: 'Acme', slug: 'acme', activeUsers: 1, invitedUsers: 0, deactivatedUsers: 0, organisationAdmins: 1, emailConfigured: true },
        };
      }
      if (url === '/api/v1/organisation/users' && init.method === 'POST') {
        return {
          status: 201,
          body: { userId: 'u2', invitationId: 'i1', link: `https://garuda.test/o/acme/invite#${TOKEN}`, emailStatus: 'SENT', expiresAt: '2026-11-16T10:00:00Z' },
        };
      }
      if (url === '/api/v1/organisation/users') {
        return {
          status: 200,
          body: [
            { id: 'u1', email: 'aaron@acme.test', displayName: 'Aaron Admin', role: 'ORG_ADMIN', status: 'ACTIVE', mfaEnrolled: true, createdAt: '2026-11-01T10:00:00Z', activatedAt: '2026-11-01T10:00:00Z', invitation: null, memberships: [] },
          ],
        };
      }
      if (url.startsWith('/api/v1/projects')) {
        return { status: 200, body: { items: [{ id: 'p1', name: 'Alpha', status: 'ACTIVE', myRole: null, memberCount: 0, createdAt: '2026-11-02T10:00:00Z' }] } };
      }
      return { status: 404 };
    });
    renderWithProviders(<UsersPage />);
    const user = userEvent.setup();
    expect(await screen.findByText('Aaron Admin')).toBeInTheDocument();
    // The admin cannot act on their own account from the list.
    expect(screen.queryByRole('button', { name: 'Actions for Aaron Admin' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Invite person/ }));
    await user.type(screen.getByLabelText('Full name'), 'Mia Member');
    await user.type(screen.getByLabelText('Work email'), 'mia@acme.test');
    await user.click(screen.getByRole('button', { name: /Add project/ }));
    await user.selectOptions(screen.getByLabelText('Project 1'), 'p1');
    await user.selectOptions(screen.getByLabelText('Role in project 1'), 'APPROVER');
    await user.click(screen.getByRole('button', { name: /Send invitation/ }));

    expect(await screen.findByRole('heading', { name: 'Invitation sent' })).toBeInTheDocument();
    expect(screen.getByLabelText('Personal link')).toHaveValue(`https://garuda.test/o/acme/invite#${TOKEN}`);
    expect(screen.getByText(/We emailed the link/)).toBeInTheDocument();
    const post = calls.find((c) => c.url === '/api/v1/organisation/users' && c.init.method === 'POST');
    expect(JSON.parse(String(post?.init.body))).toEqual({
      email: 'mia@acme.test',
      displayName: 'Mia Member',
      role: 'MEMBER',
      memberships: [{ projectId: 'p1', role: 'APPROVER' }],
    });
  });
});

describe('Invitation acceptance', () => {
  function renderInvite() {
    return renderWithProviders(
      <Routes>
        <Route path="/o/:slug/invite" element={<AccountLinkPage purpose="INVITE" />} />
      </Routes>,
      `/o/acme/invite#${TOKEN}`,
    );
  }

  it('sends the token only in the POST body and activates the account', async () => {
    document.cookie = 'XSRF-TOKEN=g2; path=/';
    const calls = mockFetch((url) => {
      if (url === '/api/v1/public/invitations/lookup') {
        return {
          status: 200,
          body: { organisationName: 'Acme', organisationSlug: 'acme', email: 'mia@acme.test', displayName: 'Mia Member', purpose: 'INVITE', expiresAt: '2026-11-16T10:00:00Z' },
        };
      }
      if (url === '/api/v1/public/invitations/accept') return { status: 204 };
      return { status: 401 };
    });
    renderInvite();
    const user = userEvent.setup();
    expect(await screen.findByRole('heading', { name: 'Welcome, Mia Member' })).toBeInTheDocument();
    await user.type(screen.getByLabelText(/^New password/), 'a long passphrase here');
    await user.type(screen.getByLabelText('Confirm password'), 'a long passphrase here');
    await user.click(screen.getByRole('button', { name: 'Activate account' }));
    expect(await screen.findByRole('heading', { name: 'Your account is ready' })).toBeInTheDocument();
    for (const c of calls) {
      expect(c.url).not.toContain(TOKEN);
    }
    const accept = calls.find((c) => c.url === '/api/v1/public/invitations/accept');
    expect(JSON.parse(String(accept?.init.body))).toEqual({ organisation: 'acme', token: TOKEN, password: 'a long passphrase here' });
  });

  it('explains an expired or used link without saying which', async () => {
    mockFetch(() => ({ status: 410, body: { error: { code: 'LINK_UNUSABLE', message: 'x', correlationId: null } } }));
    renderInvite();
    expect(await screen.findByRole('heading', { name: /This link can.t be used/ })).toBeInTheDocument();
  });
});

const staffMe = {
  staffId: 's1',
  email: 'ops@garuda.test',
  displayName: 'Olive Ops',
  activeTenant: null,
  assignedTenants: [],
  capabilities: ['ORG_ONBOARD'],
};

function onboardingRequest(overrides: Record<string, unknown>) {
  return {
    id: 'r1',
    organisationName: 'Globex',
    organisationSlug: 'globex',
    adminEmail: 'hank@globex.test',
    adminDisplayName: 'Hank Scorpio',
    status: 'REQUESTED',
    requestedBy: 's2',
    requestedByName: 'Sam Second',
    requestedAt: '2026-11-13T09:00:00Z',
    approvedBy: null,
    approvedByName: null,
    executedAt: null,
    createdTenantId: null,
    decisionNote: null,
    ...overrides,
  };
}

describe('Garuda onboarding', () => {
  it('offers approval only for requests made by someone else, and shows the invitation link', async () => {
    document.cookie = 'XSRF-TOKEN=g3; path=/';
    window.confirm = () => true;
    mockFetch((url) => {
      if (url === '/api/v1/staff/me') return { status: 200, body: staffMe };
      if (url === '/api/v1/staff/organisation-requests') {
        return {
          status: 200,
          body: [onboardingRequest({}), onboardingRequest({ id: 'r2', organisationName: 'Initech', organisationSlug: 'initech', requestedBy: 's1', requestedByName: 'Olive Ops' })],
        };
      }
      if (url === '/api/v1/staff/organisation-status-requests') return { status: 200, body: [] };
      if (url === '/api/v1/staff/organisation-requests/r1/approve') {
        return {
          status: 200,
          body: {
            request: onboardingRequest({ status: 'EXECUTED', createdTenantId: 't9', approvedBy: 's1', approvedByName: 'Olive Ops' }),
            invitationLink: `https://garuda.test/o/globex/invite#${TOKEN}`,
            emailStatus: 'NOT_SENT',
          },
        };
      }
      return { status: 404 };
    });
    renderWithProviders(<StaffOnboardingPage />);
    const user = userEvent.setup();
    expect(await screen.findByText('Globex')).toBeInTheDocument();
    // Own request: no approve button, only cancellation.
    expect(screen.getByText('Needs a second approver')).toBeInTheDocument();
    const approveButtons = screen.getAllByRole('button', { name: /Approve/ });
    expect(approveButtons).toHaveLength(1);
    await user.click(approveButtons[0]!);
    expect(await screen.findByRole('heading', { name: 'Globex is live' })).toBeInTheDocument();
    expect(screen.getByLabelText('Invitation link')).toHaveValue(`https://garuda.test/o/globex/invite#${TOKEN}`);
    expect(screen.getByText(/Email is not set up/)).toBeInTheDocument();
  });
});

describe('Garuda dashboard', () => {
  it('shows organisations, stage counts and bottlenecks with titles only', async () => {
    mockFetch((url) => {
      if (url === '/api/v1/staff/platform/dashboard') {
        return {
          status: 200,
          body: {
            organisations: 1,
            activeOrganisations: 1,
            suspendedOrganisations: 0,
            users: 4,
            projects: 2,
            repositories: 1,
            requirements: 3,
            requirementsByState: { AWAITING_CLARIFICATION: 1, INTERNAL_REVIEW: 1, SHARED_WITH_CLIENT: 1 },
            organisationRows: [
              {
                id: 't1', name: 'Acme', slug: 'acme', status: 'ACTIVE', createdAt: '2026-11-01T10:00:00Z', activeUsers: 4, invitedUsers: 1,
                projects: 2, repositories: 1, requirements: 3, openRequirements: 2, bottlenecks: 1, lastActivityAt: '2026-11-12T10:00:00Z',
                admins: [{ displayName: 'Aaron Admin', email: 'aaron@acme.test', status: 'ACTIVE' }],
              },
            ],
            bottlenecks: [
              {
                organisationId: 't1', organisationName: 'Acme', projectId: 'p1', projectName: 'Alpha', key: 'REQ-000007', title: 'Loyalty programme',
                state: 'AWAITING_CLARIFICATION', createdAt: '2026-11-01T10:00:00Z', stateSince: '2026-11-03T10:00:00Z', daysInState: 10, bottleneck: 'WAITING_FOR_CLIENT',
              },
            ],
            staleDays: 5,
          },
        };
      }
      return { status: 404 };
    });
    renderWithProviders(<StaffDashboardPage />);
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getAllByText('Acme').length).toBeGreaterThan(0);
    expect(screen.getByText('Loyalty programme')).toBeInTheDocument();
    expect(screen.getByText('Waiting for client', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.getByText(/aaron@acme.test/)).toBeInTheDocument();
  });
});
