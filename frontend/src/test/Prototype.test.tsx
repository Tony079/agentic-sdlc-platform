import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuditHistory } from '../components/AuditHistory';
import { DevelopmentAdaptersBanner, MockOutputNotice } from '../components/MockNotice';
import { WorkflowProgress } from '../components/WorkflowProgress';
import { DashboardPage } from '../pages/DashboardPage';
import { mockFetch, renderWithProviders } from './render';

const ME = { userId: 'u1', displayName: 'Alice', email: 'alice@acme.test', tenant: { id: 't1', name: 'Acme' }, roles: ['CLIENT_USER'] };

function requirement(id: string, state: string) {
  return { id, key: `REQ-00000${id}`, title: `Requirement ${id}`, state, projectId: 'p1', currentRevisionNo: 1, updatedAt: '2026-11-12T10:00:00Z' };
}

function dashboardFetch(overrides: Record<string, { status: number; body?: unknown }>) {
  return mockFetch((url) => {
    if (overrides[url]) return overrides[url];
    if (url === '/api/v1/me') return { status: 200, body: ME };
    if (url === '/api/v1/projects') return { status: 200, body: { items: [{ id: 'p1', name: 'Alpha', createdAt: '2026-11-02T10:00:00Z' }] } };
    if (url === '/api/v1/requirements') return { status: 200, body: { items: [], nextCursor: null } };
    if (url === '/api/v1/clarifications') return { status: 200, body: [] };
    return { status: 404 };
  });
}

describe('DashboardPage', () => {
  it('shows counts, recent requirements and open clarifications from the API', async () => {
    dashboardFetch({
      '/api/v1/requirements': {
        status: 200,
        body: { items: [requirement('1', 'DRAFT'), requirement('2', 'ANALYSING'), requirement('3', 'AWAITING_CLARIFICATION'), requirement('4', 'SHARED_WITH_CLIENT')], nextCursor: null },
      },
      '/api/v1/clarifications': { status: 200, body: [{ id: 'c1', requirementId: '3', status: 'OPEN' }, { id: 'c2', requirementId: '3', status: 'RESOLVED' }] },
    });
    renderWithProviders(<DashboardPage />);

    expect(screen.getByText('Loading your dashboard…')).toBeInTheDocument();
    expect(await screen.findByText('1 clarification is waiting for your answer.', { exact: false })).toBeInTheDocument();
    const tile = (label: string) => screen.getByText(label).parentElement?.querySelector('.tile-count')?.textContent;
    expect(tile('Drafts')).toBe('1');
    expect(tile('In progress')).toBe('1');
    expect(tile('Waiting for your answer')).toBe('1');
    expect(tile('BRD shared with you')).toBe('1');
    expect(tile('Ended')).toBe('0');
    expect(screen.getByRole('link', { name: 'REQ-000002' })).toHaveAttribute('href', '/requirements/2');
  });

  it('guides a new user when nothing exists yet', async () => {
    dashboardFetch({ '/api/v1/projects': { status: 200, body: { items: [] } } });
    renderWithProviders(<DashboardPage />);
    expect(await screen.findByText('You have no requirements yet.', { exact: false })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'creating a project' })).toHaveAttribute('href', '/projects');
  });

  it('reports a loading failure', async () => {
    dashboardFetch({ '/api/v1/requirements': { status: 500 } });
    renderWithProviders(<DashboardPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
  });
});

describe('AuditHistory', () => {
  it('loads on request and labels who acted without showing ids', async () => {
    const calls = mockFetch((url) => {
      if (url === '/api/v1/requirements/r1/audit') {
        return {
          status: 200,
          body: [
            { occurredAt: '2026-11-12T10:00:00Z', actor: 'YOU', action: 'REQUIREMENT_CREATED', resourceType: 'REQUIREMENT', result: 'SUCCESS' },
            { occurredAt: '2026-11-12T10:05:00Z', actor: 'STAFF', action: 'BRD_APPROVED', resourceType: 'BRD_VERSION', result: 'SUCCESS' },
          ],
        };
      }
      return { status: 404 };
    });
    renderWithProviders(<AuditHistory requirementId="r1" />);
    expect(calls.filter((c) => c.url.endsWith('/audit'))).toHaveLength(0);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Show audit history' }));
    expect(await screen.findByText('Requirement created')).toBeInTheDocument();
    expect(screen.getByText('You')).toBeInTheDocument();
    expect(screen.getByText('Brd approved')).toBeInTheDocument();
    expect(screen.getByText('Platform staff')).toBeInTheDocument();
  });

  it('shows empty and error states', async () => {
    let status = 200;
    mockFetch(() => (status === 200 ? { status, body: [] } : { status }));
    const user = userEvent.setup();
    renderWithProviders(<AuditHistory requirementId="r1" />);
    await user.click(screen.getByRole('button', { name: 'Show audit history' }));
    expect(await screen.findByText('No audit entries yet.')).toBeInTheDocument();
    status = 500;
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
  });
});

describe('development adapter labelling', () => {
  it('marks a mock BRD version and stays silent for a real provider', () => {
    const { rerender } = render(<MockOutputNotice provider="development-mock" />);
    expect(screen.getByRole('note')).toHaveTextContent('No language model analysed the requirement');
    rerender(<MockOutputNotice provider="anthropic" />);
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });

  it('shows a site banner only while development adapters are configured', async () => {
    let body: unknown = { modelProvider: 'development-mock', repositorySource: 'development', developmentAdapters: ['MODEL', 'REPOSITORY'] };
    mockFetch((url) => (url === '/api/v1/runtime' ? { status: 200, body } : { status: 401 }));
    const first = renderWithProviders(<DevelopmentAdaptersBanner />);
    const banner = await screen.findByText('Prototype mode.');
    expect(banner.parentElement).toHaveTextContent('not from a language model');
    expect(banner.parentElement).toHaveTextContent('not from GitHub');
    first.unmount();

    body = { modelProvider: 'anthropic', repositorySource: 'github', developmentAdapters: [] };
    const second = renderWithProviders(<DevelopmentAdaptersBanner />);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(second.container).not.toHaveTextContent('Prototype mode');
  });
});

describe('WorkflowProgress', () => {
  it('marks the current step and reports an ended workflow', () => {
    const { rerender } = render(<WorkflowProgress state="AWAITING_CLARIFICATION" />);
    expect(screen.getByText('Analysis')).toHaveAttribute('aria-current', 'step');
    expect(screen.getByText('Submitted')).toHaveClass('done');
    expect(screen.getByText('Approved')).toHaveClass('todo');
    rerender(<WorkflowProgress state="CANCELLED" />);
    expect(screen.getByText('Cancelled')).toBeInTheDocument();
  });
});
