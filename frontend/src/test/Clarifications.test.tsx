import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { ClarificationsPage } from '../pages/ClarificationsPage';
import { StaffClarificationsPage } from '../pages/staff/StaffClarificationsPage';
import { StaffReviewPage } from '../pages/staff/StaffReviewPage';
import { mockFetch, renderWithProviders } from './render';

const question = {
  id: 'c1', requirementId: 'r1', source: 'AGENT', blocking: true, assignedRole: 'REQUIREMENT_OWNER', status: 'OPEN',
  question: 'Which repayment date applies?', whyItMatters: 'The screen shows one date.',
  interpretations: 'Next due date, or final date', recommendedOption: 'Next due date',
  createdAt: '2026-11-08T10:00:00Z', messages: [],
};

const item = (key: string, statement: string) => ({
  itemKey: key, ordinal: 1, kind: 'FACT', proposedKind: 'FACT', section: 'FACTS', statement, risk: null,
  component: null, supportState: 'VERIFIED', rejectedCitations: 0, acknowledged: false, evidence: [],
});

describe('Clarifications', () => {
  it('shows the question with its context and sends an answer', async () => {
    document.cookie = 'XSRF-TOKEN=c1; path=/';
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/api/v1/clarifications') && (!init.method || init.method === 'GET')) {
        return { status: 200, body: [question] };
      }
      if (url.endsWith('/clarifications/c1/answer')) return { status: 200, body: { status: 'ANSWERED' } };
      return { status: 404 };
    });
    renderWithProviders(<ClarificationsPage />, '/clarifications');
    expect(await screen.findByRole('heading', { name: 'Which repayment date applies?' })).toBeInTheDocument();
    expect(screen.getByText(/The screen shows one date/)).toBeInTheDocument();
    expect(screen.getByText(/Next due date, or final date/)).toBeInTheDocument();
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: 'Answer' })).toBeDisabled();
    await user.type(screen.getByLabelText('Your answer or reason'), 'The next due date.');
    await user.click(screen.getByRole('button', { name: 'Answer' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Answer sent.');
    const answer = calls.find((c) => c.url.endsWith('/clarifications/c1/answer'));
    expect(JSON.parse(String(answer?.init.body))).toEqual({ text: 'The next due date.' });
  });

  it('lets a reviewer exclude a document that needs a decision', async () => {
    document.cookie = 'XSRF-TOKEN=c2; path=/';
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/staff/clarifications')) return { status: 200, body: [] };
      if (url.endsWith('/staff/document-decisions') && init.method === 'POST') return { status: 201 };
      if (url.endsWith('/staff/document-decisions')) {
        return { status: 200, body: [{ requirementId: 'r1', requirementKey: 'REQ-000001', documentId: 'd1',
          versionId: 'v1', fileName: 'scan.pdf', state: 'UNSUPPORTED_CONTENT', reasonCode: 'IMAGE_ONLY' }] };
      }
      return { status: 404 };
    });
    renderWithProviders(<StaffClarificationsPage />, '/staff/inbox');
    expect(await screen.findByText('scan.pdf')).toBeInTheDocument();
    expect(screen.getByText('No questions are waiting.')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Reason for exclusion'), 'Image only; not needed.');
    await user.click(screen.getByRole('button', { name: 'Exclude document' }));
    expect(await screen.findByRole('status')).toHaveTextContent('scan.pdf is excluded');
    const post = calls.find((c) => c.url.endsWith('/staff/document-decisions') && c.init.method === 'POST');
    expect(JSON.parse(String(post?.init.body))).toEqual({ versionId: 'v1', reason: 'Image only; not needed.' });
  });

  it('compares two BRD versions on the review page', async () => {
    mockFetch((url) => {
      if (url.includes('/diff?against=v1')) {
        return { status: 200, body: {
          from: { id: 'v1', versionNo: 1, status: 'SUPERSEDED', contentHash: 'a' },
          to: { id: 'v2', versionNo: 2, status: 'IN_REVIEW', contentHash: 'b' },
          added: [item('facts-2', 'Repayment dates are kept in the ledger.')],
          removed: [item('facts-3', 'The billing module encrypts invoices.')], changed: [], unchanged: 1 } };
      }
      if (url.endsWith('/staff/brd-versions/v2')) {
        return { status: 200, body: {
          requirementKey: 'REQ-000001', state: 'INTERNAL_REVIEW',
          requirement: { title: 'Loan dates', description: null },
          version: { id: 'v2', requirementId: 'r1', versionNo: 2, status: 'IN_REVIEW', contentHash: 'b'.repeat(64),
            inputHash: 'c', understanding: null, excludedDocuments: '[]', modelProvider: 'fake', modelId: 'm',
            createdAt: '2026-11-08T10:00:00Z', items: [] },
          versions: [{ id: 'v2', versionNo: 2, status: 'IN_REVIEW', contentHash: 'b' },
            { id: 'v1', versionNo: 1, status: 'SUPERSEDED', contentHash: 'a' }] } };
      }
      return { status: 404 };
    });
    renderWithProviders(
      <Routes>
        <Route path="/staff/reviews/:versionId" element={<StaffReviewPage />} />
      </Routes>,
      '/staff/reviews/v2',
    );
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Compare with'), 'v1');
    expect(await screen.findByText(/1 added, 1 removed, 0 changed, 1 unchanged/)).toBeInTheDocument();
    expect(screen.getByText(/kept in the ledger/)).toBeInTheDocument();
    expect(screen.getByText(/encrypts invoices/)).toBeInTheDocument();
  });
});
