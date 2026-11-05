import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { RequirementDetailPage } from '../pages/RequirementDetailPage';
import { mockFetch, renderWithProviders } from './render';

function detail(overrides: Record<string, unknown> = {}) {
  return {
    id: 'r1',
    key: 'REQ-000042',
    projectId: 'p1',
    state: 'DRAFT',
    version: 3,
    currentRevisionNo: 2,
    content: {
      kind: 'NEW_PRODUCT',
      title: 'Loyalty programme',
      description: 'Points for purchases',
      businessObjective: 'Retention',
      expectedOutcomes: 'More repeat customers',
      acceptanceCriteria: null,
      repositoryUrl: null,
      constraints: 'None',
      integrations: 'Not applicable',
    },
    revisions: [
      { revisionNo: 1, contentHash: 'a', createdAt: '2026-11-03T10:00:00Z' },
      { revisionNo: 2, contentHash: 'b', createdAt: '2026-11-03T11:00:00Z' },
    ],
    createdAt: '2026-11-03T10:00:00Z',
    updatedAt: '2026-11-03T11:00:00Z',
    ...overrides,
  };
}

const timeline = [{ seq: 1, fromState: null, toState: 'DRAFT', transition: 'CREATE', occurredAt: '2026-11-03T10:00:00Z' }];

function renderDetail() {
  return renderWithProviders(
    <Routes>
      <Route path="/requirements/:id" element={<RequirementDetailPage />} />
    </Routes>,
    '/requirements/r1',
  );
}

describe('RequirementDetailPage', () => {
  it('explains manual intake, offers cancellation and no editing once routed', async () => {
    mockFetch((url) =>
      url.endsWith('/timeline') ? { status: 200, body: timeline } : { status: 200, body: detail({ state: 'MANUAL_INTAKE' }) },
    );
    renderDetail();
    expect(await screen.findByRole('status')).toHaveTextContent('kept for manual intake');
    expect(screen.getByRole('button', { name: 'Cancel requirement' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument();
  });

  it('says a submitted requirement is being routed', async () => {
    mockFetch((url) =>
      url.endsWith('/timeline') ? { status: 200, body: timeline } : { status: 200, body: detail({ state: 'SUBMITTED' }) },
    );
    renderDetail();
    expect(await screen.findByRole('status')).toHaveTextContent('routing the requirement');
    expect(screen.getByRole('link', { name: 'Edit' })).toBeInTheDocument();
  });

  it('shows key, state, content and actions for a draft', async () => {
    mockFetch((url) => (url.endsWith('/timeline') ? { status: 200, body: timeline } : { status: 200, body: detail() }));
    renderDetail();
    expect(await screen.findByRole('heading', { name: 'REQ-000042: Loyalty programme' })).toBeInTheDocument();
    expect(screen.getByText('DRAFT', { selector: '.state' })).toBeInTheDocument();
    expect(screen.getByText('Not applicable')).toBeInTheDocument();
    expect(screen.queryByText('Repository URL')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit' })).toBeInTheDocument();
  });

  it('submits with If-Match and shows the new state', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const calls = mockFetch((url) => {
      if (url.endsWith('/timeline')) return { status: 200, body: timeline };
      if (url.endsWith('/submit')) return { status: 200, body: detail({ state: 'SUBMITTED', version: 4 }) };
      return { status: 200, body: detail() };
    });
    renderDetail();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Submit' }));
    expect(await screen.findByText('SUBMITTED', { selector: '.state' })).toBeInTheDocument();
    const submit = calls.find((c) => c.url.endsWith('/submit'));
    expect((submit?.init.headers as Record<string, string>)['If-Match']).toBe('"3"');
    expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument();
  });

  it('explains a version conflict and offers a reload', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    mockFetch((url) => {
      if (url.endsWith('/timeline')) return { status: 200, body: timeline };
      if (url.endsWith('/submit')) {
        return { status: 409, body: { error: { code: 'VERSION_CHANGED', message: 'x', correlationId: null } } };
      }
      return { status: 200, body: detail() };
    });
    renderDetail();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Submit' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This requirement was changed elsewhere');
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });

  it('cancels only with a reason of at least 10 characters', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    const calls = mockFetch((url) => {
      if (url.endsWith('/timeline')) return { status: 200, body: timeline };
      if (url.endsWith('/cancel')) return { status: 200, body: detail({ state: 'CANCELLED', version: 4 }) };
      return { status: 200, body: detail() };
    });
    renderDetail();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Cancel requirement' }));
    const confirm = screen.getByRole('button', { name: 'Confirm cancellation' });
    await user.type(screen.getByLabelText(/Reason for cancelling/), 'too short');
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason for cancelling/), ' and now long enough');
    await user.click(confirm);
    expect(await screen.findByText('CANCELLED', { selector: '.state' })).toBeInTheDocument();
    const cancel = calls.find((c) => c.url.endsWith('/cancel'));
    expect(JSON.parse(cancel?.init.body as string)).toEqual({ reason: 'too short and now long enough' });
    expect(screen.queryByRole('link', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('treats a 404 as not found without revealing anything', async () => {
    mockFetch(() => ({ status: 404, body: { error: { code: 'NOT_FOUND', message: 'x', correlationId: null } } }));
    renderDetail();
    expect(await screen.findByRole('alert')).toHaveTextContent('Requirement not found.');
  });
});
