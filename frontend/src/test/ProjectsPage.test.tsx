import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProjectsPage } from '../pages/ProjectsPage';
import { mockFetch, renderWithProviders } from './render';

const admin = {
  userId: 'u1',
  displayName: 'Aaron Admin',
  email: 'aaron@acme.test',
  tenant: { id: 't1', name: 'Acme' },
  roles: ['ORG_ADMIN'],
};
const member = { ...admin, roles: ['MEMBER'] };

describe('ProjectsPage', () => {
  it('lists the projects with the caller’s role in each', async () => {
    mockFetch((url) => {
      if (url === '/api/v1/me') return { status: 200, body: member };
      return {
        status: 200,
        body: {
          items: [
            { id: 'p1', name: 'Alpha', status: 'ACTIVE', myRole: 'REQUESTER', memberCount: 3, createdAt: '2026-11-02T10:00:00Z' },
          ],
        },
      };
    });
    renderWithProviders(<ProjectsPage />);
    expect(await screen.findByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Requester')).toBeInTheDocument();
    expect(screen.getByText('3 members')).toBeInTheDocument();
    // Members cannot create projects: no button is offered (the server refuses anyway).
    expect(screen.queryByRole('button', { name: /New project/ })).not.toBeInTheDocument();
  });

  it('lets an Organisation Admin create a project with the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=token-abc; path=/';
    const items: Record<string, unknown>[] = [];
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 200, body: admin };
      if (url === '/api/v1/projects' && init.method === 'POST') {
        const body = JSON.parse(init.body as string);
        const project = { id: 'p2', name: body.name, description: body.description, status: 'ACTIVE', myRole: null, memberCount: 0, createdAt: '2026-11-02T10:00:00Z' };
        items.push(project);
        return { status: 201, body: project };
      }
      if (url.startsWith('/api/v1/projects')) return { status: 200, body: { items } };
      return { status: 401 };
    });
    renderWithProviders(<ProjectsPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /New project/ }));
    await user.type(screen.getByLabelText('Project name'), '  Beta  ');
    await user.click(screen.getByRole('button', { name: 'Create project' }));

    expect(await screen.findByText('Beta')).toBeInTheDocument();
    const post = calls.find((c) => c.init.method === 'POST');
    expect((post?.init.headers as Record<string, string>)['X-CSRF-Token']).toBe('token-abc');
    expect(JSON.parse(post?.init.body as string)).toEqual({ name: 'Beta', description: null });
  });

  it('explains a duplicate-name conflict', async () => {
    document.cookie = 'XSRF-TOKEN=token-abc; path=/';
    mockFetch((url, init) => {
      if (url === '/api/v1/me') return { status: 200, body: admin };
      if (init.method === 'POST') {
        return { status: 409, body: { error: { code: 'PROJECT_NAME_TAKEN', message: 'x', correlationId: null } } };
      }
      return { status: 200, body: { items: [] } };
    });
    renderWithProviders(<ProjectsPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /New project/ }));
    await user.type(screen.getByLabelText('Project name'), 'Alpha');
    await user.click(screen.getByRole('button', { name: 'Create project' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A project with this name already exists.');
  });
});
