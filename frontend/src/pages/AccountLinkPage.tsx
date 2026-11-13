import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { AccountLinkDetails } from '../api/types';
import { AuthLayout } from '../components/AuthLayout';
import { Icon } from '../ui/Icon';
import { Callout, ErrorText, Loading, formatDateTime } from '../ui/components';

/** Rough strength feedback only; the server enforces the rule (12 to 200 characters). */
function strength(password: string): { label: string; percent: number; tone: string } {
  let score = 0;
  if (password.length >= 12) score++;
  if (password.length >= 16) score++;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score++;
  if (/\d/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password)) score++;
  if (password.length < 12) return { label: 'Too short', percent: 15, tone: 'var(--danger)' };
  if (score <= 2) return { label: 'Fair', percent: 45, tone: 'var(--warning)' };
  if (score <= 3) return { label: 'Good', percent: 70, tone: 'var(--info)' };
  return { label: 'Strong', percent: 100, tone: 'var(--success)' };
}

/**
 * Invitation acceptance and password reset (FR-ORG-07/08). The token is read from the URL fragment, which browsers do
 * not send to servers, and is sent only in a POST body.
 */
export function AccountLinkPage({ purpose }: { purpose: 'INVITE' | 'PASSWORD_RESET' }) {
  const { slug = '' } = useParams();
  const location = useLocation();
  const token = location.hash.replace(/^#/, '');
  const [details, setDetails] = useState<AccountLinkDetails | null>(null);
  const [unusable, setUnusable] = useState(false);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const invite = purpose === 'INVITE';

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      return;
    }
    api
      .post<AccountLinkDetails>(invite ? '/api/v1/public/invitations/lookup' : '/api/v1/public/password-resets/lookup', {
        organisation: slug,
        token,
      })
      .then((d) => !cancelled && setDetails(d))
      .catch(() => !cancelled && setUnusable(true));
    return () => {
      cancelled = true;
    };
  }, [invite, slug, token]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (password !== confirm) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      await api.post(invite ? '/api/v1/public/invitations/accept' : '/api/v1/public/password-resets/complete', {
        organisation: slug,
        token,
        password,
      });
      setDone(true);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'LINK_UNUSABLE') {
        setUnusable(true);
      } else if (e instanceof ApiError && e.status === 422) {
        setError('Choose a password of 12 to 200 characters.');
      } else {
        setError('Your password could not be saved. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  const s = strength(password);
  return (
    <AuthLayout>
      {done ? (
        <>
          <div className="stat-icon" style={{ width: 40, height: 40, marginBottom: '1rem' }}>
            <Icon name="checkCircle" size={22} />
          </div>
          <h1>{invite ? 'Your account is ready' : 'Password changed'}</h1>
          <p className="lead">
            {invite
              ? 'You can now sign in to your organisation. Organisation Admins will set up two-step verification at first sign-in.'
              : 'Sign in with your new password.'}
          </p>
          <Link to={`/o/${slug}/login`} className="btn btn-primary btn-lg btn-block">
            Continue to sign in
          </Link>
        </>
      ) : unusable || !token ? (
        <>
          <h1>This link can&apos;t be used</h1>
          <Callout tone="warning">
            The link has expired, was already used or was replaced by a newer one. Ask your Organisation Admin for a new{' '}
            {invite ? 'invitation' : 'password reset link'}
            {invite ? '.' : ', or request one yourself.'}
          </Callout>
          <div className="auth-links">
            <Link to={`/o/${slug}/login`}>Back to sign in</Link>
            {!invite && <Link to={`/o/${slug}/forgot`}>Request a new link</Link>}
          </div>
        </>
      ) : !details ? (
        <Loading label="Checking your link…" />
      ) : (
        <>
          <div className="eyebrow">{details.organisationName}</div>
          <h1>{invite ? `Welcome, ${details.displayName}` : 'Choose a new password'}</h1>
          <p className="lead">
            {invite ? (
              <>
                You were invited to <strong>{details.organisationName}</strong> on Garuda. Set a password for{' '}
                <strong>{details.email}</strong> to activate your account.
              </>
            ) : (
              <>
                For <strong>{details.email}</strong>.
              </>
            )}
          </p>
          <form onSubmit={(e) => void handleSubmit(e)} noValidate>
            <label>
              New password
              <input
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                aria-describedby="pw-hint"
                required
              />
              {password && (
                <span className="meter" aria-hidden="true">
                  <span style={{ width: `${s.percent}%`, background: s.tone }} />
                </span>
              )}
              <span className="hint" id="pw-hint">
                At least 12 characters. {password ? `Strength: ${s.label}.` : 'A passphrase of several words works well.'}
              </span>
            </label>
            <label>
              Confirm password
              <input
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                aria-invalid={!!confirm && confirm !== password}
                required
              />
            </label>
            {error && <ErrorText>{error}</ErrorText>}
            <button type="submit" disabled={busy || password.length < 12 || !confirm}>
              {busy ? 'Saving…' : invite ? 'Activate account' : 'Change password'}
            </button>
          </form>
          <p className="small" style={{ marginTop: '1rem' }}>
            This link expires {formatDateTime(details.expiresAt)} and works once.
          </p>
        </>
      )}
    </AuthLayout>
  );
}

/** "Forgot password": always the same answer, so it reveals nothing about which accounts exist. */
export function ForgotPasswordPage() {
  const { slug } = useParams();
  const [organisation, setOrganisation] = useState(slug ?? '');
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/api/v1/public/password-resets', { organisation: organisation.trim(), email: email.trim() });
      setSent(true);
    } catch {
      setError('The request could not be sent. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  const back = organisation.trim() ? `/o/${organisation.trim().toLowerCase()}/login` : '/login';
  return (
    <AuthLayout>
      <h1>Reset your password</h1>
      {sent ? (
        <>
          <Callout tone="success">
            If an active account matches, we&apos;ve emailed a reset link to {email}. It expires in one hour. If no email
            arrives, ask your Organisation Admin to send you a reset link.
          </Callout>
          <Link to={back}>Back to sign in</Link>
        </>
      ) : (
        <>
          <p className="lead">Enter the email you sign in with and we&apos;ll send you a link.</p>
          <form onSubmit={(e) => void handleSubmit(e)} noValidate>
            {!slug && (
              <label>
                Organisation
                <input value={organisation} onChange={(e) => setOrganisation(e.target.value)} required />
              </label>
            )}
            <label>
              Email
              <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </label>
            {error && <ErrorText>{error}</ErrorText>}
            <button type="submit" disabled={busy || !organisation.trim() || !email.trim()}>
              {busy ? 'Sending…' : 'Send reset link'}
            </button>
          </form>
          <div className="auth-links">
            <Link to={back}>Back to sign in</Link>
          </div>
        </>
      )}
    </AuthLayout>
  );
}
