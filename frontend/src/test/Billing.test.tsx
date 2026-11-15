import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrdQuotePanel } from '../components/BrdQuotePanel';
import { AiCostReport } from '../components/AiCostReport';
import { ToastProvider } from '../ui/Toast';
import { mockFetch, renderWithProviders } from './render';

const quote = (over: Record<string, unknown> = {}) => ({
  id: 'q1', status: 'PROPOSED', size: 'MEDIUM', estimated: true, documents: 1, hasRepository: true, textChars: 420,
  currency: 'INR', listPrice: 9000, planName: 'Growth', includedPerMonth: 2, includedUsedThisMonth: 2,
  wouldBeIncluded: false, included: null, charge: null, quotedAt: '2026-11-15T08:00:00Z', acceptedByName: null,
  acceptedAt: null, declinedByName: null, canDecide: true, ...over,
});

describe('BrdQuotePanel', () => {
  it('shows the overage price and lets the owner accept', async () => {
    document.cookie = 'XSRF-TOKEN=q1; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 401 };
      if (init.method === 'POST') return { status: 200, body: quote({ status: 'ACCEPTED' }) };
      return { status: 200, body: quote() };
    });
    renderWithProviders(<ToastProvider><BrdQuotePanel requirementId="r1" /></ToastProvider>);
    expect(await screen.findByText('Waiting for acceptance')).toBeInTheDocument();
    expect(screen.getByText(/all 2 included BRDs used this month/)).toBeInTheDocument();
    expect(screen.getByText('(estimated)')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Accept quote' }));
    expect(await screen.findByText(/Quote accepted/)).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/v1/requirements/r1/brd-quote/accept')).toBe(true);
  });

  it('renders nothing for a project without billing', async () => {
    mockFetch(() => ({ status: 404, body: { error: { code: 'NOT_FOUND', message: 'x', correlationId: null } } }));
    const { container } = renderWithProviders(<BrdQuotePanel requirementId="r1" />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('.card')).toBeNull();
  });
});

describe('AiCostReport', () => {
  it('drills down from organisation to project', async () => {
    const calls = mockFetch((url) => {
      if (url === '/api/v1/me') return { status: 401 };
      const project = url.includes('tenantId=');
      return {
        status: 200,
        body: {
          month: '2026-11', level: project ? 'PROJECT' : 'ORGANISATION', totalUsd: 1.5, includesEstimates: !project,
          rows: [{ tenantId: 't1', organisationName: 'Acme', projectId: project ? 'p1' : null, projectName: project ? 'Payments' : null,
            requirementId: null, requirementKey: null, calls: 3, inputTokens: 3000, outputTokens: 600, costUsd: 1.5,
            estimatedCalls: project ? 0 : 1, unmeteredCalls: 0 }],
        },
      };
    });
    renderWithProviders(<AiCostReport month="2026-11" />);
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Acme' }));
    expect(await screen.findByRole('button', { name: 'Payments' })).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/v1/staff/billing/ai-costs?month=2026-11&tenantId=t1')).toBe(true);
  });
});
