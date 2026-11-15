import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EnvironmentsTab } from '../components/EnvironmentsTab';
import type { Project } from '../api/types';
import { ToastProvider } from '../ui/Toast';
import { mockFetch, renderWithProviders } from './render';

const me = (userId: string) => ({
  userId, displayName: 'User', email: 'u@acme.test', tenant: { id: 't1', name: 'Acme' }, roles: ['CLIENT'], orgAdmin: false,
});
const project = { id: 'p1', name: 'Payments', status: 'ACTIVE', myRole: 'BRD_APPROVER', memberCount: 3, createdAt: '2026-11-02T10:00:00Z' } as unknown as Project;
const overview = (over: Record<string, unknown> = {}) => ({
  projectId: 'p1', projectStatus: 'ACTIVE', environmentManagerId: 'em', environmentManagerName: 'Alice', canManage: false,
  canApproveProduction: true,
  repositories: [{ id: 'r1', fullName: 'acme/payments', url: 'https://github.com/acme/payments', status: 'VERIFIED', statusReason: null, defaultBranch: 'main' }],
  environments: [
    { id: 'e1', name: 'QC', description: null, production: false, branches: [
      { id: 'b1', repositoryId: 'r1', repository: 'acme/payments', branch: 'release/qc', status: 'VERIFIED', headSha: 'a'.repeat(40), failureCode: null, verifiedAt: '2026-11-14T10:00:00Z' }] },
    { id: 'e2', name: 'PROD', description: null, production: true, branches: [] },
  ],
  requests: [{ id: 'q1', environmentId: 'e2', environmentName: 'PROD', kind: 'ADD_BRANCH', repository: 'acme/payments', branch: 'main',
    newName: null, reason: 'Production branch.', status: 'REQUESTED', requestedBy: 'em', requestedByName: 'Alice',
    requestedAt: '2026-11-14T10:00:00Z', approvedByName: null, executedAt: null }],
  ...over,
});

describe('EnvironmentsTab', () => {
  it('shows environments, branches and lets a BRD Approver approve a PROD change', async () => {
    document.cookie = 'XSRF-TOKEN=e1; path=/';
    window.confirm = () => true;
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 200, body: me('aaron') };
      if (init.method === 'POST') return { status: 200, body: { applied: true, requestId: null } };
      return { status: 200, body: overview() };
    });
    renderWithProviders(<ToastProvider><EnvironmentsTab project={project} /></ToastProvider>);
    expect(await screen.findByText('release/qc')).toBeInTheDocument();
    expect(screen.getByText('PROD: add acme/payments → main')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add environment/ })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Approved and applied.');
    expect(calls.some((c) => c.url === '/api/v1/environment-requests/q1/approve')).toBe(true);
  });

  it('hides approval from the requester and asks the manager for a reason on PROD changes', async () => {
    document.cookie = 'XSRF-TOKEN=e2; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 200, body: me('em') };
      if (init.method === 'POST') return { status: 200, body: { applied: false, requestId: 'q2' } };
      return { status: 200, body: overview({ canManage: true, canApproveProduction: false }) };
    });
    renderWithProviders(<ToastProvider><EnvironmentsTab project={project} /></ToastProvider>);
    expect(await screen.findByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /Branch/ })[1]!);
    const submit = screen.getByRole('button', { name: 'Request approval' });
    await user.selectOptions(screen.getByLabelText('Repository'), 'r1');
    await user.type(screen.getByLabelText('Branch'), 'main');
    expect(submit).toBeDisabled();
    await user.type(screen.getByLabelText('Reason for the approver'), 'Go live.');
    await user.click(submit);
    expect(await screen.findByRole('status')).toHaveTextContent('Sent to a BRD Approver for approval.');
    const post = calls.find((c) => c.url === '/api/v1/environments/e2/branches');
    expect(JSON.parse(String(post?.init.body))).toEqual({ repositoryId: 'r1', branch: 'main', reason: 'Go live.' });
  });
});

describe('SourcesPanel', () => {
  const sources = (over: Record<string, unknown> = {}) => ({
    environmentId: 'e2', environmentName: 'PROD', production: true, state: 'SHARED_WITH_CLIENT', locked: true,
    canChangeEnvironment: false, analysedAt: '2026-11-15T10:00:00Z',
    analysed: [
      { repositoryId: 'r1', repository: 'acme/loans', branch: 'main', analysedCommit: 'a'.repeat(40), currentCommit: 'c'.repeat(40), moved: true },
      { repositoryId: 'r2', repository: 'acme/billing', branch: 'main', analysedCommit: 'b'.repeat(40), currentCommit: 'b'.repeat(40), moved: false },
    ],
    preparing: [], branchesMoved: true, newRepositories: false, canReanalyse: true, reanalyseHint: null, ...over,
  });

  it('shows moved branches without changing anything and re-analyses on request', async () => {
    document.cookie = 'XSRF-TOKEN=s1; path=/';
    window.confirm = () => true;
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 401 };
      if (init.method === 'POST') return { status: 200, body: sources({ canReanalyse: false }) };
      if (url.endsWith('/sources')) return { status: 200, body: sources() };
      return { status: 200, body: overview() };
    });
    const { SourcesPanel } = await import('../components/SourcesPanel');
    renderWithProviders(<ToastProvider><SourcesPanel requirementId="req1" projectId="p1" /></ToastProvider>);
    expect(await screen.findByText(`Moved to ${'c'.repeat(12)}`)).toBeInTheDocument();
    expect(screen.getByText('Unchanged')).toBeInTheDocument();
    expect(screen.getByText('The code has changed since this analysis')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Re-analyse with the latest code' }));
    expect(await screen.findByText(/Re-analysis started/)).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/v1/requirements/req1/reanalyse')).toBe(true);
  });

  it('explains why re-analysis is not available before release', async () => {
    mockFetch((url) => {
      if (url === '/api/v1/me') return { status: 401 };
      if (url.endsWith('/sources')) {
        return { status: 200, body: sources({ state: 'INTERNAL_REVIEW', canReanalyse: false,
          reanalyseHint: 'Re-analysis is available once the BRD has been released to you.' }) };
      }
      return { status: 200, body: overview() };
    });
    const { SourcesPanel } = await import('../components/SourcesPanel');
    renderWithProviders(<SourcesPanel requirementId="req1" projectId="p1" />);
    expect(await screen.findByText('Re-analysis is available once the BRD has been released to you.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Re-analyse/ })).not.toBeInTheDocument();
  });
});
