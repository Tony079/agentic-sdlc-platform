import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/AuthLayout';
import { MfaStep } from '../components/MfaStep';
import { Icon } from '../ui/Icon';
import { ErrorText } from '../ui/components';

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMITED') return 'Too many attempts. Please wait and try again.';
    if (error.code === 'INVALID_CREDENTIALS') return 'The organisation, email or password is incorrect.';
  }
  return 'Sign-in failed. Please try again.';
}

/**
 * Organisation sign-in. Each organisation has its own address (/o/{code}/login) so people never type the code; the
 * generic /login asks for it. Organisation Admins then complete two-step verification.
 */
export function LoginPage() {
  const { slug } = useParams();
  const { me, login, startEnrolment, confirmEnrolment, verifyMfa, logout } = useAuth();
  const navigate = useNavigate();
  const [organisation, setOrganisation] = useState(slug ?? '');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [mfa, setMfa] = useState<{ enrolled: boolean } | null>(null);

  if (me) {
    return <Navigate to="/" replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const outcome = await login(organisation.trim(), email.trim(), password);
      if (outcome.kind === 'mfa') {
        setMfa({ enrolled: outcome.enrolled });
      } else {
        navigate('/', { replace: true });
      }
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setSubmitting(false);
      setPassword('');
    }
  }

  if (mfa) {
    return (
      <AuthLayout>
        <MfaStep
          enrolled={mfa.enrolled}
          startEnrolment={startEnrolment}
          submit={async (code) => {
            await (mfa.enrolled ? verifyMfa(code) : confirmEnrolment(code));
            navigate('/', { replace: true });
          }}
          onCancel={() => {
            void logout().catch(() => undefined);
            setMfa(null);
          }}
        />
      </AuthLayout>
    );
  }

  const forgot = organisation.trim() ? `/o/${encodeURIComponent(organisation.trim().toLowerCase())}/forgot` : '/forgot-password';
  return (
    <AuthLayout>
      <h1>Sign in</h1>
      <p className="lead">Welcome back. Sign in to your organisation&apos;s workspace.</p>
      {slug && (
        <div className="org-banner">
          <span className="stat-icon">
            <Icon name="building" size={16} />
          </span>
          <div>
            <div className="small">Organisation</div>
            <strong>{slug}</strong>
          </div>
          <Link to="/login" className="small" style={{ marginLeft: 'auto' }}>
            Change
          </Link>
        </div>
      )}
      <form onSubmit={(e) => void handleSubmit(e)} noValidate>
        {!slug && (
          <div className="field">
            <label htmlFor="login-organisation" style={{ margin: 0 }}>
              Organisation
            </label>
            <input
              id="login-organisation"
              value={organisation}
              onChange={(e) => setOrganisation(e.target.value)}
              autoComplete="organization"
              placeholder="your-organisation"
              aria-describedby="login-organisation-hint"
              required
            />
            <span className="hint" id="login-organisation-hint">
              The sign-in code from your invitation email.
            </span>
          </div>
        )}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <span className="copy-field">
            <input
              type={show ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              aria-label="Password"
              required
            />
            <button
              type="button"
              className="btn-secondary btn-icon"
              aria-label={show ? 'Hide password' : 'Show password'}
              onClick={() => setShow((s) => !s)}
            >
              <Icon name={show ? 'eyeOff' : 'eye'} size={16} />
            </button>
          </span>
        </label>
        {error && <ErrorText>{error}</ErrorText>}
        <button type="submit" disabled={submitting || !organisation.trim() || !email || !password}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <div className="auth-links">
        <Link to={forgot}>Forgot password?</Link>
        <Link to="/staff/login" className="muted">
          Garuda staff
        </Link>
      </div>
    </AuthLayout>
  );
}
