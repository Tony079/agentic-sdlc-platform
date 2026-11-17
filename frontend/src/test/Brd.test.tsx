import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { BrdPanel } from '../components/BrdPanel';
import { StaffReviewPage } from '../pages/staff/StaffReviewPage';
import { mockFetch, renderWithProviders } from './render';

const item = (over: Record<string, unknown>) => ({
  itemKey: 'facts-1', ordinal: 1, kind: 'FACT', proposedKind: 'FACT', section: 'FACTS',
  statement: 'Loans are served at /loans.', risk: null, component: null, supportState: 'VERIFIED', rejectedCitations: 0,
  acknowledged: false,
  evidence: [{ type: 'REPOSITORY', path: 'src/Loan.java', startLine: 3, endLine: 6, commitSha: 'abcdef0123456789',
    documentVersionId: null, fileName: null, chunk: null, chunkLabel: null, quote: null, supportPassed: null }],
  ...over,
});
const version = (items: unknown[], status = 'RELEASED') => ({
  id: 'v1', requirementId: 'r1', versionNo: 1, status, contentHash: 'c'.repeat(64), inputHash: 'i'.repeat(64),
  understanding: 'Repayment metadata.', excludedDocuments: '[]', modelProvider: 'fake', modelId: 'fake-1',
  createdAt: '2026-11-07T10:00:00Z', items,
});

const review = (over: Record<string, unknown> = {}) => ({
  stage: 'AUTHOR_REVIEW', feasibility: 'FEASIBLE_WITH_RISKS', feasibilityReasons: ['The loan API can carry the metadata.'],
  unacceptedFlags: ['facts-2'], acceptedFlags: [], authorConfirmedBy: null, authorConfirmedAt: null, approvedBy: null,
  revisionsUsed: 0, revisionsIncluded: 3, nextRevisionCurrency: null, nextRevisionPrice: null, isAuthor: true,
  isApprover: false, canAcceptFlags: true, canConfirm: false, canApprove: false, canRequestChanges: true,
  blockedReason: '1 flagged statement(s) need your decision before you confirm.', ...over,
});

describe('BrdPanel (client)', () => {
  it('explains that the agent is still working before a BRD exists', async () => {
    mockFetch(() => ({ status: 404, body: { error: { code: 'NOT_FOUND', message: 'x', correlationId: null } } }));
    renderWithProviders(<BrdPanel requirementId="r1" requirementState="ANALYSING" />);
    expect(await screen.findByText(/The BA agent is analysing the code and documents/)).toBeInTheDocument();
  });

  it('lets the author accept a flagged statement, shows the verdict, and requests changes', async () => {
    document.cookie = 'XSRF-TOKEN=b1; path=/';
    const flagged = item({ itemKey: 'facts-2', statement: 'Billing encrypts invoices.', supportState: 'FLAGGED' });
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: {} };
      if (url.endsWith('/brd')) {
        return { status: 200, body: { requirementId: 'r1', state: 'INTERNAL_REVIEW', version: version([item({}), flagged], 'IN_REVIEW'), review: review() } };
      }
      return { status: 401 };
    });
    renderWithProviders(<BrdPanel requirementId="r1" requirementState="INTERNAL_REVIEW" />);
    expect(await screen.findByText('Feasibility: Feasible with risks')).toBeInTheDocument();
    expect(screen.getByText('Author review')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm BRD' })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Accept as is' }));
    expect(calls.some((c) => c.url === '/api/v1/requirements/r1/brd/flags/facts-2/accept')).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Request changes' }));
    expect(screen.getByText('3 of 3 included change requests left.')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/What should change/), 'Please also cover refunds.');
    await user.click(screen.getByRole('button', { name: 'Send change request' }));
    const post = calls.find((c) => c.url.endsWith('/brd/request-changes'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ text: 'Please also cover refunds.', acceptCharge: false });
  });

  it('lets a BRD Approver approve a confirmed BRD bound to its content', async () => {
    document.cookie = 'XSRF-TOKEN=b2; path=/';
    window.confirm = () => true;
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: {} };
      if (url.endsWith('/brd')) {
        return { status: 200, body: { requirementId: 'r1', state: 'INTERNAL_REVIEW', version: version([item({})], 'IN_REVIEW'),
          review: review({ stage: 'AWAITING_APPROVAL', unacceptedFlags: [], isAuthor: false, isApprover: true, canAcceptFlags: false,
            canApprove: true, authorConfirmedBy: 'Alice', authorConfirmedAt: '2026-11-16T10:00:00Z', blockedReason: null }) } };
      }
      return { status: 401 };
    });
    renderWithProviders(<BrdPanel requirementId="r1" requirementState="INTERNAL_REVIEW" />);
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Approve BRD' }));
    const post = calls.find((c) => c.url.endsWith('/brd/approve'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ expectedContentHash: 'c'.repeat(64) });
  });
});

describe('StaffReviewPage', () => {
  it('acknowledges flags and approves the exact version shown', async () => {
    document.cookie = 'XSRF-TOKEN=r1; path=/';
    const flagged = item({ itemKey: 'facts-2', statement: 'Billing encrypts invoices.', supportState: 'FLAGGED',
      evidence: [{ ...item({}).evidence[0], quote: 'class LoanController', supportPassed: false }] });
    const calls = mockFetch((_url, init) => {
      if (init.method === 'POST') return { status: 200, body: { state: 'INTERNAL_APPROVED' } };
      return {
        status: 200,
        body: { requirementKey: 'REQ-000001', state: 'INTERNAL_REVIEW', version: version([item({}), flagged], 'IN_REVIEW'),
          requirement: { kind: 'EXISTING_REPOSITORY', title: 'Repayment metadata', description: null, businessObjective: null,
            expectedOutcomes: null, acceptanceCriteria: null, repositoryUrl: null, constraints: null, integrations: null } },
      };
    });
    renderWithProviders(
      <Routes>
        <Route path="/staff/reviews/:versionId" element={<StaffReviewPage />} />
      </Routes>,
      '/staff/reviews/v1',
    );
    expect(await screen.findByText('class LoanController')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Acknowledge flag' }));
    expect(calls.some((c) => c.url === '/api/v1/staff/brd-versions/v1/flags/facts-2/acknowledge')).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Approve this version' }));
    const approve = calls.find((c) => c.url.endsWith('/approve'));
    expect(JSON.parse(String(approve?.init.body))).toEqual({ expectedContentHash: 'c'.repeat(64) });
  });
});

describe('AnalysisRetryPanel', () => {
  it('explains the failure and lets the author retry', async () => {
    document.cookie = 'XSRF-TOKEN=r9; path=/';
    const { AnalysisRetryPanel } = await import('../components/AnalysisRetryPanel');
    const calls = mockFetch((_url, init) => ({
      status: 200,
      body: { failed: true, canRetry: init.method !== 'POST', used: init.method === 'POST' ? 1 : 0, limit: 3,
        failureCode: 'MODEL_INVALID_OUTPUT', message: '3 of 3 retries left. A retry is not charged again.', pending: false },
    }));
    renderWithProviders(<AnalysisRetryPanel requirementId="r1" />);
    expect(await screen.findByText(/could not use/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry analysis' }));
    expect(calls.some((c) => c.url === '/api/v1/requirements/r1/analysis-retry' && c.init.method === 'POST')).toBe(true);
  });
});
