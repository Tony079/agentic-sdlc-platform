import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RepositoryLinkPanel } from '../components/RepositoryLinkPanel';
import { RedeemLinkPage } from '../pages/RedeemLinkPage';
import { mockFetch, renderWithProviders } from './render';

const TOKEN = 'rlr_' + 'a'.repeat(43);

describe('RepositoryLinkPanel (requirement owner)', () => {
  it('issues a one-time code and shows it once', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    const calls = mockFetch((_url, init) => {
      if (init.method === 'POST') {
        return { status: 201, body: { requestId: 'q1', token: TOKEN, expiresAt: '2026-11-10T10:00:00Z' } };
      }
      return { status: 200, body: { pendingRequest: null, link: null, validation: null } };
    });
    renderWithProviders(<RepositoryLinkPanel requirementId="r1" requirementState="SUBMITTED" />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Ask an approver to link a repository' }));
    expect(await screen.findByLabelText('One-time link code')).toHaveTextContent(TOKEN);
    expect(calls.find((c) => c.init.method === 'POST')?.url).toBe('/api/v1/requirements/r1/repository-link-requests');
  });

  it('shows the registered repository, pinned commit and snapshot, and offers no new code', async () => {
    mockFetch(() => ({
      status: 200,
      body: {
        pendingRequest: null,
        link: { linkId: 'l1', repositoryUrl: 'https://github.com/acme/loans', status: 'REGISTERED', authorizedAt: '2026-11-07T10:00:00Z' },
        validation: {
          status: 'VALIDATED', pinnedSha: '0123456789abcdef0123456789abcdef01234567', defaultBranch: 'main',
          failureCode: null, snapshotStatus: 'INDEXED', fileCount: 12,
        },
      },
    }));
    renderWithProviders(<RepositoryLinkPanel requirementId="r1" requirementState="AWAITING_DOCUMENTS" />);
    expect(await screen.findByText(/Registered by the platform team/)).toBeInTheDocument();
    expect(screen.getByText('0123456789ab')).toBeInTheDocument();
    expect(screen.getByText(/Snapshot: indexed/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approver/ })).not.toBeInTheDocument();
  });
});

describe('RedeemLinkPage (Client Approver)', () => {
  it('records the authorisation and shows only a reference', async () => {
    document.cookie = 'XSRF-TOKEN=t2; path=/';
    const calls = mockFetch((url) =>
      url.endsWith('/redeem') ? { status: 201, body: { linkId: 'abcdef12-0000', status: 'AUTHORIZED' } } : { status: 401 },
    );
    renderWithProviders(<RedeemLinkPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('One-time code'), TOKEN);
    await user.type(screen.getByLabelText('Repository URL'), 'https://github.com/acme/loans');
    await user.click(screen.getByRole('button', { name: 'Authorise repository' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Repository authorised. Reference abcdef12.');
    const redeem = calls.find((c) => c.url.endsWith('/redeem'));
    expect(JSON.parse(String(redeem?.init.body))).toEqual({ token: TOKEN, repositoryUrl: 'https://github.com/acme/loans' });
  });

  it('shows one generic message for any invalid code', async () => {
    document.cookie = 'XSRF-TOKEN=t3; path=/';
    mockFetch((url) =>
      url.endsWith('/redeem')
        ? { status: 404, body: { error: { code: 'NOT_FOUND', message: 'The resource was not found.', correlationId: null } } }
        : { status: 401 },
    );
    renderWithProviders(<RedeemLinkPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('One-time code'), 'rlr_wrong');
    await user.type(screen.getByLabelText('Repository URL'), 'https://github.com/acme/loans');
    await user.click(screen.getByRole('button', { name: 'Authorise repository' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('This code is not valid.');
  });
});
