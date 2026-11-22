import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DevelopmentPanel } from '../components/DevelopmentPanel';
import { mockFetch, renderWithProviders } from './render';

const task = (over: Record<string, unknown>) => ({
  id: 't1', storyKey: 'story-a', title: 'As a developer, I want the API skeleton', wave: 1, external: false,
  issueNumber: 7, status: 'PR_OPEN', attempts: 1, branch: 'garuda/req-000003/story-a', prNumber: 12,
  prUrl: 'https://github.com/acme/site/pull/12', summary: 'Added the health endpoint with a test.', testsPassed: true,
  filesChanged: 3, failureCode: null, reason: null, updatedAt: '2026-11-21T10:00:00Z', ...over,
});

const run = {
  id: 'r1', status: 'RUNNING', repository: 'acme/site', currentWave: 1, agentSlots: 2, startedAt: '2026-11-21T09:00:00Z',
  finishedAt: null, counts: {},
  tasks: [
    task({}),
    task({ id: 't2', storyKey: 'story-b', title: 'As an author, I want a dark theme', status: 'FAILED', prNumber: null,
      prUrl: null, summary: null, testsPassed: null, filesChanged: null, failureCode: 'GITHUB_CODE_PERMISSION',
      reason: 'the Garuda GitHub App has not been granted Contents and Pull requests: Read and write on this repository' }),
    task({ id: 't3', storyKey: 'story-c', title: 'As an author, I want my pages', wave: 2, status: 'WAITING', prNumber: null, prUrl: null }),
    task({ id: 'o1', storyKey: 'oauth', title: 'Register the OAuth apps', wave: 0, external: true, status: 'WAITING', prNumber: null, prUrl: null }),
  ],
};

describe('DevelopmentPanel (GD-36)', () => {
  it('explains how development starts when there is none yet', async () => {
    mockFetch(() => ({ status: 200, body: { requirementId: 'x', canControl: false, run: null } }));
    renderWithProviders(<DevelopmentPanel requirementId="x" />);
    expect(await screen.findByText(/starts on its own when an approved delivery plan for coding agents is pushed/)).toBeInTheDocument();
  });

  it('shows waves, pull requests and outside work, and lets a Technical Approver retry a failed story', async () => {
    document.cookie = 'XSRF-TOKEN=d1; path=/';
    const body = { requirementId: 'x', canControl: true, run };
    const calls = mockFetch((url) => ({ status: 200, body: url.endsWith('/development') || url.includes('/development/') ? body : [] }));
    renderWithProviders(<DevelopmentPanel requirementId="x" />);
    expect(await screen.findByRole('link', { name: 'pull request #12' })).toHaveAttribute('href', 'https://github.com/acme/site/pull/12');
    const outside = screen.getByRole('region', { name: 'Outside work' });
    expect(within(outside).getByText('Register the OAuth apps')).toBeInTheDocument();
    // Outside work can be marked done here (after a confirmation), releasing the waves that wait for it.
    window.confirm = () => true;
    await userEvent.setup().click(within(outside).getByRole('button', { name: 'Mark done' }));
    expect(calls.some((c) => c.url.endsWith('/development/tasks/o1/done'))).toBe(true);
    const wave1 = screen.getByRole('region', { name: 'Wave 1' });
    const failed = within(wave1).getByText('As an author, I want a dark theme').closest('li') as HTMLElement;
    expect(within(failed).getByText(/Contents and Pull requests/)).toBeInTheDocument();
    await userEvent.setup().click(within(failed).getByRole('button', { name: 'Retry' }));
    expect(calls.some((c) => c.url.endsWith('/development/tasks/t2/retry'))).toBe(true);
    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
  });

  it('folds a finished wave to one line, opens a failed story\'s log, and changes the branch', async () => {
    localStorage.clear();
    document.cookie = 'XSRF-TOKEN=d2; path=/';
    const later = {
      ...run, currentWave: 2, baseBranch: 'main', branchFrom: "the repository's default branch",
      branchOptions: [{ branch: 'main', environment: 'PROD', production: true },
        { branch: 'releases/qc', environment: 'QC', production: false }],
      tasks: [
        task({ status: 'MERGED' }),
        task({ id: 't2', storyKey: 'story-b', title: 'As an author, I want a dark theme', wave: 2, status: 'FAILED',
          prNumber: null, prUrl: null, failureCode: 'AGENT_GAVE_UP', reason: 'the Developer agent could not implement it',
          draft: true }),
      ],
    };
    const log = [
      { id: 1, stage: 'DEVELOPMENT', runId: 'r1', taskId: 't2', kind: 'STEP', message: '"Dark theme", session 1: GIVE_UP · no tool calls', createdAt: '2026-11-21T10:00:00Z' },
    ];
    const calls = mockFetch((url) => ({
      status: 200,
      body: url.endsWith('/tasks/t2/log') ? log : url.endsWith('/development') || url.endsWith('/base-branch')
        || url.endsWith('/mode')
        ? { requirementId: 'x', canControl: true, run: later } : [],
    }));
    renderWithProviders(<DevelopmentPanel requirementId="x" />);
    // Wave 1 is finished: one line with what was merged, its stories hidden until opened.
    const wave1 = await screen.findByRole('region', { name: 'Wave 1' });
    expect(within(wave1).getByText(/1 merged · #12/)).toBeInTheDocument();
    expect(within(wave1).queryByText('As a developer, I want the API skeleton')).not.toBeInTheDocument();
    await userEvent.setup().click(within(wave1).getByRole('button', { name: /Wave 1/ }));
    expect(within(wave1).getByText('As a developer, I want the API skeleton')).toBeInTheDocument();
    // The failed story's own log is open and loaded.
    const wave2 = screen.getByRole('region', { name: 'Wave 2' });
    expect(within(wave2).getAllByText('needs attention')).toHaveLength(2); // the wave and the story
    expect(await within(wave2).findByText(/session 1: GIVE_UP/)).toBeInTheDocument();
    // One story at a time: why the wave is waiting, and the switch to several at once.
    expect(within(wave2).getByText(/needs attention first/)).toBeInTheDocument();
    await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: /Stories/ }), 'PARALLEL');
    expect(JSON.parse(String(calls.find((c) => c.url.endsWith('/development/mode'))?.init.body))).toEqual({ mode: 'PARALLEL' });
    // The branch is shown with where it comes from; Change reveals the choice.
    expect(screen.getByText(/the repository's default branch|from the/)).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Change' }));
    await user.selectOptions(screen.getByRole('combobox', { name: /Works on branch/ }), 'releases/qc');
    const post = calls.find((c) => c.url.endsWith('/development/base-branch'));
    expect(JSON.parse(String(post?.init.body))).toEqual({ branch: 'releases/qc' });
  });

  it('offers to start development for a plan pushed before the Developer agent existed', async () => {
    document.cookie = 'XSRF-TOKEN=d1; path=/';
    const calls = mockFetch(() => ({ status: 200, body: { requirementId: 'x', canControl: true, run: null, canStart: true,
      notStarted: 'The plan was pushed to acme/site but development has not started yet.' } }));
    renderWithProviders(<DevelopmentPanel requirementId="x" />);
    expect(await screen.findByText(/pushed to acme\/site but development has not started/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Start development' }));
    expect(calls.some((c) => c.url.endsWith('/development/start'))).toBe(true);
  });
});
