import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { mockFetch, renderWithProviders } from './render';

const me = {
  userId: 'u1',
  displayName: 'Alice Client',
  email: 'alice@acme.test',
  tenant: { id: 't1', name: 'Acme Corporation' },
  roles: ['CLIENT_USER'],
};

async function fillAndSubmit() {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Organisation'), 'acme');
  await user.type(screen.getByLabelText('Email'), 'alice@acme.test');
  await user.type(screen.getByLabelText('Password'), 'secret-password');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  it('redirects to sign-in when there is no session', async () => {
    mockFetch(() => ({ status: 401, body: { error: { code: 'UNAUTHENTICATED', message: 'x', correlationId: null } } }));
    renderWithProviders(<App />, '/projects');
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('obtains a CSRF token, signs in with it, and shows the dashboard', async () => {
    let signedIn = false;
    const calls = mockFetch((url, init) => {
      if (url === '/api/v1/me') return signedIn ? { status: 200, body: me } : { status: 401 };
      if (url === '/api/v1/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=token-123; path=/';
        return { status: 200, body: { headerName: 'X-CSRF-Token', token: 'token-123' } };
      }
      if (url === '/api/v1/auth/login' && init.method === 'POST') {
        signedIn = true;
        return { status: 200, body: me };
      }
      if (url === '/api/v1/projects') return { status: 200, body: { items: [] } };
      if (url === '/api/v1/requirements') return { status: 200, body: { items: [], nextCursor: null } };
      if (url === '/api/v1/clarifications') return { status: 200, body: [] };
      return { status: 404 };
    });
    renderWithProviders(<App />, '/login');
    await fillAndSubmit();

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByText('Alice Client · Acme Corporation')).toBeInTheDocument();
    const login = calls.find((c) => c.url === '/api/v1/auth/login');
    expect((login?.init.headers as Record<string, string>)['X-CSRF-Token']).toBe('token-123');
    expect(JSON.parse(login?.init.body as string)).toEqual({
      organisation: 'acme',
      email: 'alice@acme.test',
      password: 'secret-password',
    });
  });

  it('shows one generic message for invalid credentials', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    mockFetch((url) => {
      if (url === '/api/v1/me') return { status: 401 };
      return {
        status: 401,
        body: { error: { code: 'INVALID_CREDENTIALS', message: 'server text', correlationId: 'c1' } },
      };
    });
    renderWithProviders(<App />, '/login');
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('The organisation, email or password is incorrect.');
  });
});
