import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { AuthLayout } from '../../components/AuthLayout';
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
    <AuthLayout variant="staff">
      <div className="eyebrow">Garuda staff</div>
      <h1>{step === 'credentials' ? 'Sign in to the console' : step === 'enrol' ? 'Set up your authenticator' : 'Two-step verification'}</h1>
      {step === 'credentials' ? (
        <>
          <p className="lead">Use your Garuda staff account. Client users sign in at their organisation&apos;s address.</p>
          <form onSubmit={signIn} noValidate>
            <label>
              Email
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
            </label>
            <label>
              Password
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
            </label>
            {error && (
              <p role="alert" className="error">
                {error}
              </p>
            )}
            <button type="submit" disabled={busy || !email || !password}>
              Continue
            </button>
          </form>
        </>
      ) : (
        <form onSubmit={confirm} noValidate>
          {enrolment ? (
            <>
              <p className="lead">Scan the code with your authenticator app, then enter the 6-digit code. The key is shown only once.</p>
              <StaffQr uri={enrolment.otpauthUri} />
              <p className="small">Or enter this key manually:</p>
              <code className="secret-key" aria-label="Authenticator key">
                {enrolment.secret}
              </code>
              <hr />
            </>
          ) : (
            <p className="lead">Enter the current code from your authenticator app.</p>
          )}
          <label>
            Authenticator code
            <input
              className="code-input"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').substring(0, 6))}
              autoFocus
            />
          </label>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button type="submit" disabled={busy || code.length !== 6}>
            Verify
          </button>
        </form>
      )}
      <div className="auth-links">
        <Link to="/login">Client sign-in</Link>
      </div>
    </AuthLayout>
  );
}

function StaffQr({ uri }: { uri: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(uri, { margin: 1, width: 184 })
      .then((d) => !cancelled && setSrc(d))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [uri]);
  return src ? (
    <div className="qr">
      <img src={src} alt="QR code for your authenticator app" width={184} height={184} />
    </div>
  ) : null;
}
