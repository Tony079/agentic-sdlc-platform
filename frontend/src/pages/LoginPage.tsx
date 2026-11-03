import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'RATE_LIMITED') return 'Too many attempts. Please wait and try again.';
    if (error.code === 'INVALID_CREDENTIALS') return 'The organisation, email or password is incorrect.';
  }
  return 'Sign-in failed. Please try again.';
}

export function LoginPage() {
  const { me, login } = useAuth();
  const navigate = useNavigate();
  const [organisation, setOrganisation] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (me) {
    return <Navigate to="/projects" replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(organisation, email, password);
      navigate('/projects', { replace: true });
    } catch (e) {
      setError(messageFor(e));
    } finally {
      setSubmitting(false);
      setPassword('');
    }
  }

  return (
    <div className="login">
      <h1>Sign in</h1>
      <form onSubmit={handleSubmit} noValidate>
        <label>
          Organisation
          <input value={organisation} onChange={(e) => setOrganisation(e.target.value)} autoComplete="organization" required />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting || !organisation || !email || !password}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
