import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { ActivityTimeline } from '../components/ActivityTimeline';
import { mockFetch, renderWithProviders } from './render';

const line = (id: number, runId: string, kind: string, message: string, seconds: number) => ({
  id, stage: 'BRD', runId, kind, message, createdAt: new Date(Date.UTC(2026, 10, 19, 10, 0, seconds)).toISOString(),
});

describe('ActivityTimeline', () => {
  it('shows the latest run step by step with elapsed time, and folds earlier attempts', async () => {
    mockFetch(() => ({ status: 200, body: [
      line(1, 'run-1', 'STEP', 'Started the analysis: reading the requirement and 1 document.', 0),
      line(2, 'run-1', 'FAIL', "The model's answer broke a rule twice (items[3].statement size must be between 1 and 2000). Garuda will try again automatically.", 40),
      line(3, 'run-2', 'STEP', 'Started the analysis (attempt 2): reading the requirement and 1 document.', 50),
      line(4, 'run-2', 'WARN', "The model's first answer broke a rule (feasibility.reasons size must be between 1 and 10). Garuda asked it to correct the answer, and the corrected answer passed.", 80),
      line(5, 'run-2', 'STEP', 'Writing the BRD: context, impact, scenarios, risks, acceptance criteria and a feasibility verdict.', 81),
      line(7, 'run-2', 'DRAFT', 'Scenarios: A candidate uploads a resume.', 85),
      line(8, 'run-2', 'DRAFT', 'Question: Which channels are allowed?', 86),
      { ...line(6, 'x', 'STEP', 'Design step', 90), stage: 'DESIGN' },
    ] }));
    renderWithProviders(<ActivityTimeline requirementId="r1" stage="BRD" live title="What the BA agent is doing" />);
    expect(await screen.findByText('What the BA agent is doing')).toBeInTheDocument();
    expect(screen.getByText(/Started the analysis \(attempt 2\)/)).toBeInTheDocument();
    expect(screen.getByText(/feasibility.reasons size must be between 1 and 10/)).toBeInTheDocument();
    expect(screen.getByText('+31s')).toBeInTheDocument();
    expect(screen.getByText('Earlier attempts (1)')).toBeInTheDocument();
    // While writing, the drafts so far are listed (open) and labelled as not checked.
    expect(screen.getByText(/Drafted so far: 2 items \(not checked yet/)).toBeInTheDocument();
    expect(screen.getByText('Scenarios: A candidate uploads a resume.')).toBeVisible();
    expect(screen.queryByText('Design step')).not.toBeInTheDocument();
  });

  it('renders nothing when there is no activity and the agent is not working', async () => {
    mockFetch(() => ({ status: 200, body: [] }));
    const { container } = renderWithProviders(<ActivityTimeline requirementId="r1" stage="DESIGN" live={false} title="How" />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container.textContent).toBe('');
  });
});
