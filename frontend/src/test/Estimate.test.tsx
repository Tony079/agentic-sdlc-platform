import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EstimatePanel } from '../components/EstimatePanel';
import { mockFetch, renderWithProviders } from './render';

const review = (over: Record<string, unknown> = {}) => ({
  stage: 'IN_REVIEW', isEstimator: true, isApprover: false, canStart: false, canRetry: false, canAdjust: true,
  canApprove: false, canRequestChanges: true, unacceptedFlags: ['est-b'], uncoveredDesignItems: [], approvedBy: null,
  approvedAt: null, approvalNote: null, designOutdated: false, currentDesignVersionNo: 1, blockedReason: null, ...over,
});

const item = (over: Record<string, unknown>) => ({
  itemKey: 'est-a', ordinal: 1, title: 'Set up the API', detail: 'Skeleton and CI.', role: 'BACKEND', phase: 'SETUP',
  designRefs: ['architecture-1'], proposedLow: 2, proposedLikely: 3, proposedHigh: 5, low: 2, likely: 3, high: 5,
  expected: 3.17, assumptions: [], risk: null, dependsOn: [], flags: [], accepted: false, adjustments: [], ...over,
});

const version = {
  id: 'v1', versionNo: 1, status: 'IN_REVIEW', designVersionNo: 1, reviewHash: 'h'.repeat(64), changeRequest: null,
  failureCode: null, attempts: 1, createdAt: '2026-11-20T10:00:00Z',
  settings: { team: { BACKEND: 2 }, daysPerWeek: 5, contingencyPercent: 10, currency: 'INR', dayRates: { BACKEND: 5000 } },
  assumptions: ['One release'], excluded: ['Custom domains'],
  items: [item({}), item({ itemKey: 'est-b', ordinal: 2, title: 'Build pages', role: 'QA', phase: 'TEST', low: 1, likely: 2,
    high: 9, flags: ['UNCERTAIN'] })],
  coverage: [{ designKey: 'architecture-1', section: 'ARCHITECTURE', title: 'Stack', covered: true, accepted: false, coveredBy: ['Set up the API'] }],
  summary: {
    effort: { p50: 6.2, p85: 7.9 }, effortWithContingency: { p50: 6.8, p85: 8.7 },
    byRole: [{ role: 'BACKEND', effortP50: 3.5, effortP85: 4.1, people: 2, assumedPerson: false, weeksP50: 0.35, weeksP85: 0.41,
      costP50: 17400, costP85: 20500 }],
    weeks: { p50: 1.4, p85: 1.7 }, criticalPathWeeks: 1.4, costP50: 30000, costP85: 36000, currency: 'INR',
    contingencyPercent: 10, notes: [],
  },
};

describe('EstimatePanel (GD-33)', () => {
  it('shows other members the status only', async () => {
    mockFetch(() => ({ status: 200, body: { requirementId: 'r1', canSee: false, review: review({ isEstimator: false, canAdjust: false, canRequestChanges: false }), version: null, history: [], defaults: null } }));
    renderWithProviders(<EstimatePanel requirementId="r1" />);
    expect(await screen.findByText(/Only the project’s Estimators and BRD Approvers can see the estimate/)).toBeInTheDocument();
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(screen.queryByText('Set up the API')).not.toBeInTheDocument();
  });

  it('lets an Estimator adjust a flagged item with a reason, showing Garuda’s totals', async () => {
    document.cookie = 'XSRF-TOKEN=e1; path=/';
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: { requirementId: 'r1', canSee: true, review: review(), version, history: [], defaults: null } };
      if (url.endsWith('/estimate')) return { status: 200, body: { requirementId: 'r1', canSee: true, review: review(), version, history: [], defaults: null } };
      return { status: 200, body: [] };
    });
    renderWithProviders(<EstimatePanel requirementId="r1" />);
    expect(await screen.findByText('6.8 – 8.7')).toBeInTheDocument();
    expect(screen.getByText('INR 30,000 – INR 36,000')).toBeInTheDocument();
    expect(screen.getByText(/Wide range/)).toBeInTheDocument();
    const user = userEvent.setup();
    const flagged = screen.getByText('Build pages').closest('li') as HTMLElement;
    await user.click(within(flagged).getByRole('button', { name: 'Adjust' }));
    const high = within(flagged).getByLabelText('High days');
    await user.clear(high);
    await user.type(high, '4');
    await user.type(within(flagged).getByLabelText(/Reason/), 'Layouts were agreed.');
    await user.click(within(flagged).getByRole('button', { name: 'Save adjustment' }));
    const post = calls.find((c) => c.url.endsWith('/estimate/items/est-b/adjust'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ low: 1, likely: 2, high: 4, reason: 'Layouts were agreed.' });
  });

  it('lets a BRD Approver approve only after confirming, bound to the figures shown', async () => {
    document.cookie = 'XSRF-TOKEN=a1; path=/';
    const approver = review({ isEstimator: false, isApprover: true, canAdjust: false, canApprove: true, unacceptedFlags: [] });
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: { requirementId: 'r1', canSee: true, review: review({ stage: 'APPROVED' }), version, history: [], defaults: null } };
      if (url.endsWith('/estimate')) return { status: 200, body: { requirementId: 'r1', canSee: true, review: approver, version, history: [], defaults: null } };
      return { status: 200, body: [] };
    });
    renderWithProviders(<EstimatePanel requirementId="r1" />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Approve estimate' }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Approve estimate' });
    expect(confirm).toBeDisabled();
    await user.click(within(dialog).getByRole('checkbox'));
    await user.click(confirm);
    const post = calls.find((c) => c.url.endsWith('/estimate/approve'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ expectedReviewHash: 'h'.repeat(64), confirmApproveAlone: true });
  });
});
