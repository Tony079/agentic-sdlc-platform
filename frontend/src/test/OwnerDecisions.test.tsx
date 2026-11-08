import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ChangeDecisionsPage } from '../pages/ChangeDecisionsPage';
import { OrganisationPage } from '../pages/OrganisationPage';
import { StaffOperationsPage } from '../pages/staff/StaffOperationsPage';
import { mockFetch, renderWithProviders } from './render';

const staff = (capabilities: string[]) => ({
  staffId: 's1', email: 's@staff.test', displayName: 'S', activeTenant: { id: 't1', name: 'Acme' },
  assignedTenants: [{ id: 't1', name: 'Acme' }], capabilities,
});

const changeRequest = {
  id: 'cr1', requirementId: 'r1', status: 'AWAITING_CLIENT_DECISION', requestedChange: 'Cover refunds too.',
  justification: 'Refunds are booked against repayments.', expectedImpact: 'Small', submittedAt: '2026-11-08T10:00:00Z',
  scopeAssessment: 'Adds refunds.', effortEstimate: '3 days', timelineImpact: 'One sprint', technicalImpact: 'New endpoint',
  reviewNote: null, decisionNote: null, baselineVersion: 1,
  diff: [{ field: 'constraints', area: 'SECURITY', changeType: 'MODIFICATION', recommendedPriority: 'CRITICAL',
    priority: 'CRITICAL', before: 'None', after: 'Encrypt refund data.', adjustmentReason: null }],
};

describe('Owner-decision workflows', () => {
  it('shows staff sections by capability only and resumes a manual-intake requirement', async () => {
    document.cookie = 'XSRF-TOKEN=o1; path=/';
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/staff/me')) return { status: 200, body: staff(['BUSINESS_REVIEWER']) };
      if (url.endsWith('/staff/manual-intake')) {
        return { status: 200, body: [{ id: 'r9', key: 'REQ-000009', title: 'New product', currentRevisionNo: 2,
          repositoryLinkAuthorised: true }] };
      }
      if (url.endsWith('/staff/change-requests')) return { status: 200, body: [] };
      if (url.endsWith('/resume') && init.method === 'POST') return { status: 200, body: { state: 'REPO_VALIDATING' } };
      return { status: 403, body: { error: { code: 'FORBIDDEN', message: 'Not allowed.', correlationId: null } } };
    });
    renderWithProviders(<StaffOperationsPage />, '/staff/operations');
    expect(await screen.findByRole('region', { name: 'Manual intake' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Releases awaiting approval' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Feature flags' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Failed jobs' })).toBeNull();
    const user = userEvent.setup();
    expect(await screen.findByText(/REQ-000009/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Resume reason'), 'Repository identified by the client.');
    await user.click(screen.getByRole('button', { name: 'Resume' }));
    expect(await screen.findByRole('status')).toHaveTextContent('REQ-000009 resumed');
    const post = calls.find((c) => c.url.endsWith('/staff/requirements/r9/resume'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ expectedRevisionNo: 2, reason: 'Repository identified by the client.' });
  });

  it('blocks release approval in the UI when a gate failed', async () => {
    mockFetch((url) => {
      if (url.endsWith('/staff/me')) return { status: 200, body: staff(['RELEASE_APPROVE']) };
      if (url.endsWith('/staff/releases')) {
        return { status: 200, body: [{ requirementKey: 'REQ-1', brdVersionId: 'v1', versionNo: 1, contentHash: 'a',
          commitSha: 'b'.repeat(40), gates: [{ name: 'SECURITY_SCAN_SECRET', passed: false, detail: 'UNAVAILABLE' }] }] };
      }
      return { status: 404 };
    });
    renderWithProviders(<StaffOperationsPage />, '/staff/operations');
    expect(await screen.findByText(/FAIL SECURITY_SCAN_SECRET/)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('cannot be approved');
    expect(screen.queryByRole('button', { name: 'Approve release' })).toBeNull();
  });

  it('lets a Client Approver see the diff and accept a change', async () => {
    document.cookie = 'XSRF-TOKEN=o2; path=/';
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/api/v1/change-requests') && (!init.method || init.method === 'GET')) {
        return { status: 200, body: [changeRequest] };
      }
      if (url.endsWith('/decision')) return { status: 200, body: { ...changeRequest, status: 'ACCEPTED' } };
      return { status: 404 };
    });
    renderWithProviders(<ChangeDecisionsPage />, '/change-requests');
    expect(await screen.findByText('Encrypt refund data.')).toBeInTheDocument();
    expect(screen.getByText('SECURITY')).toBeInTheDocument();
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: 'Decline change' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Accept change' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Accepted');
    const decision = calls.find((c) => c.url.endsWith('/change-requests/cr1/decision'));
    expect(JSON.parse(String(decision?.init.body))).toEqual({ decision: 'ACCEPT', note: '' });
  });

  it('records an organisation deletion request without deleting anything', async () => {
    document.cookie = 'XSRF-TOKEN=o3; path=/';
    mockFetch((url, init) => {
      if (url.endsWith('/tenant/deletion-requests') && init.method === 'POST') return { status: 201, body: {} };
      if (url.endsWith('/tenant/deletion-requests')) return { status: 200, body: [] };
      return { status: 404 };
    });
    renderWithProviders(<OrganisationPage />, '/organisation');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Reason (at least 10 characters)'), 'Contract ended in October.');
    await user.click(screen.getByRole('button', { name: 'Request deletion' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Two authorised staff members must verify and approve');
  });
});
