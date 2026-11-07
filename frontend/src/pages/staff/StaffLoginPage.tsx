import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { StaffMe } from '../../api/types';

type Step = 'credentials' | 'enrol' | 'verify';

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMITED') return 'Too many attempts. Please wait and try again.';
    if (error.code === 'INVALID_CREDENTIALS') return 'The email or password is incorrect.';
    if (error.code === 'MFA_FAILED') return 'The code is incorrect or was already used. Wait for the next code.';
    if (error.code === 'MFA_ENROLMENT_CLOSED') return 'MFA enrolment is not open for this account. Ask a staff administrator.';
  }
  return fallback;
}

/** Internal staff sign-in: password, then TOTP (enrolment on first use). O-11: nothing is reachable before MFA. */
export function StaffLoginPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>('credentials');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [enrolment, setEnrolment] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function signIn(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const result = await api.post<{ status: string }>('/api/v1/staff/auth/login', { email, password });
      if (result.status === 'MFA_ENROLMENT_REQUIRED') {
        setEnrolment(await api.post<{ secret: string; otpauthUri: string }>('/api/v1/staff/auth/mfa/enrolment'));
        setStep('enrol');
      } else {
        setStep('verify');
      }
    } catch (e) {
      setError(message(e, 'Sign-in failed. Please try again.'));
    } finally {
      setPassword('');
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const path = step === 'enrol' ? '/api/v1/staff/auth/mfa/enrolment/confirm' : '/api/v1/staff/auth/mfa/verify';
      await api.post<StaffMe>(path, { code });
      setEnrolment(null);
      navigate('/staff', { replace: true });
    } catch (e) {
      setError(message(e, 'Verification failed.'));
    } finally {
      setCode('');
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <h1>Staff sign-in</h1>
      {step === 'credentials' ? (
        <form onSubmit={signIn} noValidate>
          <label>
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </label>
          <label>
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </label>
          <button type="submit" disabled={busy || !email || !password}>
            Continue
          </button>
        </form>
      ) : (
        <form onSubmit={confirm} noValidate>
          {enrolment && (
            <div className="notice">
              <p>Add this account to your authenticator app, then enter the current code. The key is shown only once.</p>
              <p>
                Key: <code aria-label="Authenticator key">{enrolment.secret}</code>
              </p>
              <p className="small">
                <code>{enrolment.otpauthUri}</code>
              </p>
            </div>
          )}
          <label>
            Authenticator code
            <input
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').substring(0, 6))}
            />
          </label>
          <button type="submit" disabled={busy || code.length !== 6}>
            Verify
          </button>
        </form>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}
