import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { RequirementFormPage } from '../pages/RequirementFormPage';
import { mockFetch, renderWithProviders } from './render';

const projects = { items: [{ id: 'p1', name: 'Loans', createdAt: '2026-11-03T10:00:00Z' }] };

function detail(overrides: Record<string, unknown> = {}) {
  return {
    id: 'r1',
    key: 'REQ-000001',
    projectId: 'p1',
    state: 'DRAFT',
    version: 0,
    currentRevisionNo: 1,
    content: {
      kind: 'EXISTING_REPOSITORY',
      title: 'Draft',
      description: null,
      businessObjective: null,
      expectedOutcomes: null,
      acceptanceCriteria: null,
      repositoryUrl: null,
      constraints: null,
      integrations: null,
    },
    revisions: [],
    createdAt: '2026-11-03T10:00:00Z',
    updatedAt: '2026-11-03T10:00:00Z',
    ...overrides,
  };
}

function renderNew() {
  return renderWithProviders(
    <Routes>
      <Route path="/requirements/new" element={<RequirementFormPage />} />
      <Route path="/requirements/:id" element={<p>Detail page</p>} />
    </Routes>,
    '/requirements/new',
  );
}

describe('RequirementForm', () => {
  it('shows the repository URL only for existing repositories and the greenfield notice for new products', async () => {
    mockFetch(() => ({ status: 200, body: projects }));
    renderNew();
    const user = userEvent.setup();
    expect(await screen.findByLabelText('Repository URL')).toBeInTheDocument();
    await user.click(screen.getByLabelText('New product'));
    expect(screen.queryByLabelText('Repository URL')).not.toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('Automated greenfield analysis and scaffolding are not supported yet');
  });

  it('creates a draft with an Idempotency-Key and only the fields entered', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/requirements' && init.method === 'POST') return { status: 201, body: detail() };
      return { status: 200, body: projects };
    });
    renderNew();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Project'), 'p1');
    await user.type(screen.getByLabelText('Title'), 'Add repayment metadata');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByText('Detail page')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST');
    const headers = post?.init.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(headers['X-CSRF-Token']).toBe('t1');
    const body = JSON.parse(post?.init.body as string);
    expect(body).toMatchObject({ projectId: 'p1', kind: 'EXISTING_REPOSITORY', title: 'Add repayment metadata' });
    expect(body).not.toHaveProperty('tenantId');
  });

  it('shows field-specific server errors and keeps the user on the form', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    mockFetch((_url, init) => {
      if (init.method === 'POST') {
        return {
          status: 422,
          body: {
            error: {
              code: 'VALIDATION_FAILED',
              message: 'x',
              correlationId: null,
              details: [
                { field: 'repositoryUrl', code: 'INVALID_FORMAT' },
                { field: 'title', code: 'REQUIRED' },
              ],
            },
          },
        };
      }
      return { status: 200, body: projects };
    });
    renderNew();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Repository URL'), 'http://example.com/x');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByText('Use the form https://github.com/owner/repository.')).toBeInTheDocument();
    expect(screen.getByLabelText('Repository URL')).toHaveAttribute('aria-invalid', 'true');
    expect(within(screen.getByRole('alert')).getByText('Please correct the highlighted fields.')).toBeInTheDocument();
  });

  it('says the draft was saved when the save succeeds but submission is incomplete', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    mockFetch((url, init) => {
      if (url === '/api/v1/requirements' && init.method === 'POST') return { status: 201, body: detail() };
      if (url === '/api/v1/requirements/r1/submit') {
        return {
          status: 422,
          body: {
            error: {
              code: 'VALIDATION_FAILED',
              message: 'x',
              correlationId: null,
              details: [{ field: 'description', code: 'REQUIRED' }],
            },
          },
        };
      }
      return { status: 200, body: projects };
    });
    renderNew();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Project'), 'p1');
    await user.type(screen.getByLabelText('Title'), 'Partial');
    await user.click(screen.getByRole('button', { name: 'Save and submit' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The draft was saved, but it cannot be submitted yet.');
    expect(screen.getByText('This field is required to submit.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Edit REQ-000001' })).toBeInTheDocument();
  });

  it('save and submit sends the submit with the saved version', async () => {
    document.cookie = 'XSRF-TOKEN=t1; path=/';
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/requirements' && init.method === 'POST') return { status: 201, body: detail({ version: 0 }) };
      if (url === '/api/v1/requirements/r1/submit') return { status: 200, body: detail({ version: 1, state: 'SUBMITTED' }) };
      return { status: 200, body: projects };
    });
    renderNew();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Project'), 'p1');
    await user.type(screen.getByLabelText('Title'), 'Complete');
    await user.click(screen.getByRole('button', { name: 'Save and submit' }));

    expect(await screen.findByText('Detail page')).toBeInTheDocument();
    const submit = calls.find((c) => c.url === '/api/v1/requirements/r1/submit');
    expect((submit?.init.headers as Record<string, string>)['If-Match']).toBe('"0"');
  });
});
