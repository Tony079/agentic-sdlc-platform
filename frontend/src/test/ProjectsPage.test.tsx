import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProjectsPage } from '../pages/ProjectsPage';
import { mockFetch, renderWithProviders } from './render';

describe('ProjectsPage', () => {
  it('lists the projects returned by the server', async () => {
    mockFetch(() => ({
      status: 200,
      body: { items: [{ id: 'p1', name: 'Alpha', createdAt: '2026-11-02T10:00:00Z' }] },
    }));
    renderWithProviders(<ProjectsPage />);
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
  });

  it('creates a project with the CSRF header and refreshes the list', async () => {
    document.cookie = 'XSRF-TOKEN=token-abc; path=/';
    const items: { id: string; name: string; createdAt: string }[] = [];
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/projects' && init.method === 'POST') {
        const project = { id: 'p2', name: JSON.parse(init.body as string).name, createdAt: '2026-11-02T10:00:00Z' };
        items.push(project);
        return { status: 201, body: project };
      }
      if (url === '/api/v1/projects') return { status: 200, body: { items } };
      return { status: 401 };
    });
    renderWithProviders(<ProjectsPage />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('New project name'), '  Beta  ');
    await user.click(screen.getByRole('button', { name: 'Create project' }));

    expect(await screen.findByText('Beta')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST');
    expect((post?.init.headers as Record<string, string>)['X-CSRF-Token']).toBe('token-abc');
    expect(JSON.parse(post?.init.body as string)).toEqual({ name: 'Beta' });
  });

  it('explains a duplicate-name conflict', async () => {
    document.cookie = 'XSRF-TOKEN=token-abc; path=/';
    mockFetch((_url, init) => {
      if (init.method === 'POST') {
        return {
          status: 409,
          body: { error: { code: 'PROJECT_NAME_TAKEN', message: 'x', correlationId: null } },
        };
      }
      return { status: 200, body: { items: [] } };
    });
    renderWithProviders(<ProjectsPage />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('New project name'), 'Alpha');
    await user.click(screen.getByRole('button', { name: 'Create project' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A project with this name already exists.');
  });
});
