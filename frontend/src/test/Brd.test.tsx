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

describe('BrdPanel (client)', () => {
  it('explains that nothing is shared before release', async () => {
    mockFetch(() => ({ status: 404, body: { error: { code: 'NOT_FOUND', message: 'x', correlationId: null } } }));
    renderWithProviders(<BrdPanel requirementId="r1" requirementState="INTERNAL_REVIEW" />);
    expect(await screen.findByText('A BRD draft is in internal review.')).toBeInTheDocument();
  });

  it('shows the released BRD with references and lets the owner request changes', async () => {
    document.cookie = 'XSRF-TOKEN=b1; path=/';
    const calls = mockFetch((url, init) => {
      if (init.method === 'POST') return { status: 200, body: { state: 'CLIENT_CHANGES_REQUESTED' } };
      if (url.endsWith('/brd')) return { status: 200, body: { requirementId: 'r1', state: 'SHARED_WITH_CLIENT', version: version([item({})]) } };
      return { status: 401 };
    });
    renderWithProviders(<BrdPanel requirementId="r1" requirementState="SHARED_WITH_CLIENT" />);
    expect(await screen.findByText('Loans are served at /loans.')).toBeInTheDocument();
    expect(screen.getByText('src/Loan.java lines 3–6 @ abcdef012345')).toBeInTheDocument();
    expect(document.querySelector('blockquote')).toBeNull();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Request changes/), 'Please also cover refunds.');
    await user.click(screen.getByRole('button', { name: 'Request changes' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Your change request was sent.');
    expect(JSON.parse(String(calls.find((c) => c.init.method === 'POST')?.init.body))).toEqual({ text: 'Please also cover refunds.' });
  });
});

describe('StaffReviewPage', () => {
  it('acknowledges flags and approves the exact version shown', async () => {
    document.cookie = 'XSRF-TOKEN=r1; path=/';
    const flagged = item({ itemKey: 'facts-2', statement: 'Billing encrypts invoices.', supportState: 'FLAGGED',
      evidence: [{ ...item({}).evidence[0], quote: 'class LoanController', supportPassed: false }] });
    const calls = mockFetch((url, init) => {
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
