import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StaffTeamPage } from '../pages/staff/StaffTeamPage';
import { mockFetch, renderWithProviders } from './render';

const me = { staffId: 's1', email: 'asha@garuda.test', displayName: 'Asha Admin', activeTenant: null, assignedTenants: [], capabilities: ['STAFF_ADMIN'] };

function member(id: string, name: string, extra: Record<string, unknown> = {}) {
  return {
    id, email: `${id}@garuda.test`, displayName: name, status: 'ACTIVE', mfaEnrolled: true, createdAt: '2026-11-01T10:00:00Z',
    capabilities: [], assignments: [], pendingLink: null, ...extra,
  };
}

function request(id: string, requestedBy: string, extra: Record<string, unknown> = {}) {
  return {
    id, kind: 'GRANT_CAPABILITY', subjectStaffId: 's3', subjectName: 'Cara Analyst', inviteEmail: null, inviteDisplayName: null,
    inviteCapabilities: [], capability: 'ORG_ONBOARD', tenantId: null, tenantName: null, reason: 'Joins onboarding.',
    status: 'REQUESTED', requestedBy, requestedByName: requestedBy === 's1' ? 'Asha Admin' : 'Ben Admin',
    requestedAt: '2026-11-13T09:00:00Z', approvedBy: null, approvedByName: null, executedAt: null, ...extra,
  };
}

describe('Garuda team', () => {
  it('invites a staff member with capabilities as a request for a second approver', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/staff/me') return { status: 200, body: me };
      if (url === '/api/v1/staff/admin/staff') return { status: 200, body: [member('s1', 'Asha Admin', { capabilities: ['STAFF_ADMIN'] })] };
      if (url === '/api/v1/staff/admin/requests' && init.method === 'POST') return { status: 201, body: request('r9', 's1') };
      if (url === '/api/v1/staff/admin/requests') return { status: 200, body: [] };
      return { status: 404 };
    });
    renderWithProviders(<StaffTeamPage />);
    const user = userEvent.setup();
    expect(await screen.findByText('Asha Admin')).toBeInTheDocument();
    // No actions on one's own account.
    expect(screen.queryByRole('button', { name: 'Actions for Asha Admin' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Invite staff member/ }));
    await user.type(screen.getByLabelText('Full name'), 'Dev Analyst');
    await user.type(screen.getByLabelText('Work email'), 'dev@garuda.test');
    await user.click(screen.getByRole('checkbox', { name: /Business analyst/ }));
    await user.type(screen.getByLabelText(/^Reason/), 'New analyst for the Acme account.');
    await user.click(screen.getByRole('button', { name: 'Request approval' }));
    const post = await vi_waitForPost(calls);
    expect(JSON.parse(String(post.init.body))).toMatchObject({
      kind: 'INVITE',
      email: 'dev@garuda.test',
      displayName: 'Dev Analyst',
      capabilities: ['BUSINESS_ANALYST'],
      reason: 'New analyst for the Acme account.',
    });
  });

  it('offers approval only for requests made by another administrator', async () => {
    mockFetch((url) => {
      if (url === '/api/v1/staff/me') return { status: 200, body: me };
      if (url === '/api/v1/staff/admin/staff') return { status: 200, body: [member('s1', 'Asha Admin')] };
      if (url === '/api/v1/staff/admin/requests') {
        return { status: 200, body: [request('r1', 's2'), request('r2', 's1', { subjectName: 'Dan Ops' })] };
      }
      return { status: 404 };
    });
    renderWithProviders(<StaffTeamPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: /Requests/ }));
    const own = (await screen.findByText(/Give Dan Ops/)).closest('tr')!;
    expect(within(own).queryByRole('button', { name: /Approve/ })).not.toBeInTheDocument();
    expect(within(own).getByText('Needs a second approver')).toBeInTheDocument();
    const other = screen.getByText(/Give Cara Analyst/).closest('tr')!;
    expect(within(other).getByRole('button', { name: /Approve/ })).toBeInTheDocument();
  });
});

async function vi_waitForPost(calls: { url: string; init: RequestInit }[]) {
  for (let i = 0; i < 50; i++) {
    const post = calls.find((c) => c.url === '/api/v1/staff/admin/requests' && c.init.method === 'POST');
    if (post) return post;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error('no POST');
}
