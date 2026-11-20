import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PlanPanel } from '../components/PlanPanel';
import { mockFetch, renderWithProviders } from './render';

const review = (over: Record<string, unknown> = {}) => ({
  stage: 'IN_REVIEW', isEstimator: true, isApprover: false, canStart: false, canRetry: false, canEdit: true,
  canApprove: false, canRequestChanges: true, unacceptedFlags: ['story-b'], openGaps: [], approvedBy: null,
  approvedAt: null, approvalNote: null, estimateOutdated: false, blockedReason: null, ...over,
});

const story = (over: Record<string, unknown>) => ({
  storyKey: 'story-a', ordinal: 1, epicKey: 'epic-1', title: 'As a developer, I want the API skeleton',
  acceptanceCriteria: ['Health answers 200'], estimateRefs: ['est-1'], brdRefs: [], role: 'BACKEND', priority: 'MUST',
  dependsOn: [], effortDays: 3.2, sprint: 1, pinned: false, flags: [], accepted: false, edits: [], ...over,
});

const version = {
  id: 'p1', versionNo: 1, status: 'IN_REVIEW', estimateVersionNo: 1, reviewHash: 'r'.repeat(64), changeRequest: null,
  failureCode: null, attempts: 1, createdAt: '2026-11-20T10:00:00Z',
  settings: { sprintWeeks: 1, startDate: '2026-12-07', daysPerWeek: 5, team: { BACKEND: 1 } }, risks: ['Templates may grow'],
  epics: [{ epicKey: 'epic-1', title: 'Platform', goal: 'A running API.', stories: 2, effortDays: 3.2 }],
  stories: [story({}), story({ storyKey: 'story-b', ordinal: 2, title: 'As a candidate, I want a dark theme', role: 'FRONTEND',
    priority: 'SHOULD', effortDays: 0, sprint: 2, flags: ['NO_ESTIMATE'] })],
  sprints: [
    { number: 1, start: '2026-12-07', end: '2026-12-13', load: { BACKEND: 3.2 }, capacity: { BACKEND: 5 }, overCapacity: false, effortDays: 3.2 },
    { number: 2, start: '2026-12-14', end: '2026-12-20', load: { FRONTEND: 0 }, capacity: { FRONTEND: 5 }, overCapacity: false, effortDays: 0 },
  ],
  gaps: [], totalEffortDays: 3.2, estimateEffortDays: 3.2, notes: [],
};

describe('PlanPanel (GD-34)', () => {
  it('keeps a draft private from other members', async () => {
    mockFetch(() => ({ status: 200, body: { requirementId: 'r1', canSee: false, insider: false,
      review: review({ isEstimator: false, canEdit: false, canRequestChanges: false }), version: null, history: [], defaults: null } }));
    renderWithProviders(<PlanPanel requirementId="r1" />);
    expect(await screen.findByText(/shown to the project once a BRD Approver approves it/)).toBeInTheDocument();
    expect(screen.queryByText(/API skeleton/)).not.toBeInTheDocument();
  });

  it('shows stories by sprint and lets an Estimator move a flagged story with a reason', async () => {
    document.cookie = 'XSRF-TOKEN=p1; path=/';
    const body = { requirementId: 'r1', canSee: true, insider: true, review: review(), version, history: [], defaults: null };
    const calls = mockFetch((url, init) => ({ status: 200, body: url.endsWith('/plan') || init.method === 'POST' ? body : [] }));
    renderWithProviders(<PlanPanel requirementId="r1" />);
    const sprint2 = await screen.findByRole('region', { name: 'Sprint 2' });
    const flagged = within(sprint2).getByText(/dark theme/).closest('li') as HTMLElement;
    expect(within(flagged).getByText(/Not linked to any estimated work/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(within(flagged).getByRole('button', { name: 'Edit' }));
    await user.selectOptions(within(flagged).getByLabelText('Priority'), 'COULD');
    const sprintInput = within(flagged).getByLabelText('Sprint');
    await user.clear(sprintInput);
    await user.type(sprintInput, '3');
    await user.type(within(flagged).getByLabelText(/Reason/), 'Nice to have after launch.');
    await user.click(within(flagged).getByRole('button', { name: 'Save changes' }));
    const post = calls.find((c) => c.url.endsWith('/plan/stories/story-b/edit'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ changes: { priority: 'COULD', sprint: 3 }, reason: 'Nice to have after launch.' });
  });
});
