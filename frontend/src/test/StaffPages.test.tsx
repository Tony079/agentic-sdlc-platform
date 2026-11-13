import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { StaffHomePage } from '../pages/staff/StaffHomePage';
import { StaffLoginPage } from '../pages/staff/StaffLoginPage';
import { mockFetch, renderWithProviders } from './render';

const staffMe = {
  staffId: 's1', email: 'reg@staff.test', displayName: 'Reg', activeTenant: { id: 't1', name: 'Acme' },
  assignedTenants: [{ id: 't1', name: 'Acme' }], capabilities: ['REPOSITORY_REGISTER'],
};

describe('Staff sign-in', () => {
  it('enrols TOTP on first sign-in and then opens the workspace', async () => {
    document.cookie = 'XSRF-TOKEN=s1; path=/';
    const calls = mockFetch((url) => {
      if (url.endsWith('/staff/auth/login')) return { status: 200, body: { status: 'MFA_ENROLMENT_REQUIRED' } };
      if (url.endsWith('/mfa/enrolment')) return { status: 200, body: { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x' } };
      if (url.endsWith('/mfa/enrolment/confirm')) return { status: 200, body: staffMe };
      if (url.endsWith('/staff/me')) return { status: 200, body: staffMe };
      if (url.endsWith('/staff/repository-links')) return { status: 200, body: [] };
      return { status: 401 };
    });
    renderWithProviders(
      <Routes>
        <Route path="/staff/login" element={<StaffLoginPage />} />
        <Route path="/staff" element={<StaffHomePage />} />
      </Routes>,
      '/staff/login',
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'reg@staff.test');
    await user.type(screen.getByLabelText('Password'), 'a-long-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByLabelText('Authenticator key')).toHaveTextContent('JBSWY3DPEHPK3PXP');
    await user.type(screen.getByLabelText('Authenticator code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByRole('heading', { name: 'Workspace' })).toBeInTheDocument();
    const confirm = calls.find((c) => c.url.endsWith('/mfa/enrolment/confirm'));
    expect(JSON.parse(String(confirm?.init.body))).toEqual({ code: '123456' });
  });

  it('shows the MFA failure message', async () => {
    document.cookie = 'XSRF-TOKEN=s2; path=/';
    mockFetch((url) => {
      if (url.endsWith('/staff/auth/login')) return { status: 200, body: { status: 'MFA_REQUIRED' } };
      if (url.endsWith('/mfa/verify')) {
        return { status: 401, body: { error: { code: 'MFA_FAILED', message: 'x', correlationId: null } } };
      }
      return { status: 401 };
    });
    renderWithProviders(<StaffLoginPage />, '/staff/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'reg@staff.test');
    await user.type(screen.getByLabelText('Password'), 'a-long-password');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.type(await screen.findByLabelText('Authenticator code'), '000000');
    await user.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The code is incorrect or was already used.');
  });
});

describe('Staff workspace', () => {
  it('registers an authorised repository link', async () => {
    document.cookie = 'XSRF-TOKEN=s3; path=/';
    const calls = mockFetch((url, init) => {
      if (url.endsWith('/staff/me')) return { status: 200, body: staffMe };
      if (url.endsWith('/staff/repository-links')) {
        return { status: 200, body: [{ linkId: 'l1', repositoryUrl: 'https://github.com/acme/loans', projectId: 'p1', authorizedAt: '2026-11-07T10:00:00Z' }] };
      }
      if (init.method === 'POST' && url.endsWith('/projects/p1/repositories')) {
        return { status: 202, body: { repositoryId: 'r1', status: 'PENDING_VERIFICATION' } };
      }
      return { status: 401 };
    });
    renderWithProviders(<StaffHomePage />, '/staff');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Register' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Registered https://github.com/acme/loans.');
    const post = calls.find((c) => c.init.method === 'POST');
    expect(JSON.parse(String(post?.init.body))).toEqual({ linkId: 'l1' });
  });

  it('sends unauthenticated visitors to the staff sign-in', async () => {
    mockFetch(() => ({ status: 401 }));
    renderWithProviders(
      <Routes>
        <Route path="/staff/login" element={<p>staff login page</p>} />
        <Route path="/staff" element={<StaffHomePage />} />
      </Routes>,
      '/staff',
    );
    expect(await screen.findByText('staff login page')).toBeInTheDocument();
  });
});
