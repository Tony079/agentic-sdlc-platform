import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DesignPanel } from '../components/DesignPanel';
import { mockFetch, renderWithProviders } from './render';

const review = (over: Record<string, unknown> = {}) => ({
  stage: 'NOT_STARTED', isAuthor: true, isApprover: false, canStart: true, canRetry: false, canAcceptFlags: false,
  canConfirm: false, canApprove: false, canRequestChanges: false, unacceptedFlags: [], confirmedBy: null,
  confirmedAt: null, approvedBy: null, approvedAt: null, brdOutdated: false, currentBrdVersionNo: 1, blockedReason: null,
  ...over,
});

const version = (over: Record<string, unknown> = {}) => ({
  id: 'd1', versionNo: 1, status: 'IN_REVIEW', brdVersionNo: 1, summary: 'Add a channel to repayments.',
  openQuestions: ['Which channels are allowed?'], contentHash: 'c'.repeat(64), modelId: 'gpt-4.1-mini',
  changeRequest: null, failureCode: null, attempts: 1, createdAt: '2026-11-17T10:00:00Z',
  items: [
    { itemKey: 'api-1', ordinal: 1, section: 'API_CHANGES', kind: 'CHANGE', title: 'Validate the channel',
      detail: 'POST /repayments rejects a missing channel.', component: 'Loan API', brdRefs: ['scenarios-1'],
      supportState: 'VERIFIED', evidence: [{ path: 'acme/loans/src/Loan.java', startLine: 3, endLine: 6, quote: 'x' }],
      accepted: false },
    { itemKey: 'data-1', ordinal: 2, section: 'DATA_MODEL', kind: 'NEW', title: 'Add repayment.channel',
      detail: 'New column.', component: null, brdRefs: [], supportState: 'FLAGGED', evidence: [], accepted: false },
  ],
  coverage: [
    { itemKey: 'scenarios-1', section: 'SCENARIOS', statement: 'A repayment without a channel is rejected.', covered: true, coveredBy: ['Validate the channel'] },
    { itemKey: 'ac-1', section: 'ACCEPTANCE_CRITERIA', statement: 'The API returns the channel.', covered: false, coveredBy: [] },
  ],
  ...over,
});

describe('DesignPanel', () => {
  it('lets the author start the technical design from an approved BRD', async () => {
    document.cookie = 'XSRF-TOKEN=d1; path=/';
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') {
        return { status: 200, body: { requirementId: 'r1', review: review({ stage: 'GENERATING', canStart: false }), version: null, history: [] } };
      }
      if (url.endsWith('/design')) return { status: 200, body: { requirementId: 'r1', review: review(), version: null, history: [] } };
      return { status: 200, body: [] };
    });
    renderWithProviders(<DesignPanel requirementId="r1" />);
    expect(await screen.findByText('Not started')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Start technical design' }));
    expect(calls.some((c) => c.url === '/api/v1/requirements/r1/design/start')).toBe(true);
    expect(await screen.findByText(/The Architect agent is writing the design/)).toBeInTheDocument();
  });

  it('shows coverage gaps, flagged items and the review actions', async () => {
    document.cookie = 'XSRF-TOKEN=d1; path=/';
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: { requirementId: 'r1', review: review({ stage: 'AUTHOR_REVIEW', canStart: false }), version: version(), history: [] } };
      if (url.endsWith('/design')) {
        return { status: 200, body: { requirementId: 'r1', version: version(), history: [],
          review: review({ stage: 'AUTHOR_REVIEW', canStart: false, canAcceptFlags: true, canRequestChanges: true, unacceptedFlags: ['data-1'] }) } };
      }
      return { status: 200, body: [] };
    });
    renderWithProviders(<DesignPanel requirementId="r1" />);
    expect(await screen.findByText('BRD coverage: 1 of 2 statements addressed')).toBeInTheDocument();
    expect(screen.getByText('The API returns the channel.')).toBeInTheDocument();
    expect(screen.getByText('Which channels are allowed?')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm design' })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Accept as is' }));
    expect(calls.some((c) => c.url === '/api/v1/requirements/r1/design/flags/data-1/accept')).toBe(true);
  });

  it('offers downloads once the design is approved', async () => {
    mockFetch((url) => (url.endsWith('/design')
      ? { status: 200, body: { requirementId: 'r1', version: version({ status: 'APPROVED' }), history: [],
          review: review({ stage: 'APPROVED', canStart: false, confirmedBy: 'Alice', approvedBy: 'Aaron' }) } }
      : { status: 200, body: [] }));
    renderWithProviders(<DesignPanel requirementId="r1" />);
    expect(await screen.findByRole('link', { name: 'Download PDF' })).toHaveAttribute('href', expect.stringContaining('/design/export?format=pdf'));
    expect(screen.getByRole('link', { name: 'Download Word' })).toBeInTheDocument();
  });
});
