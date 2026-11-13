import { useEffect, useRef, useState, type FormEvent } from 'react';
import QRCode from 'qrcode';
import { ApiError } from '../api/client';
import type { MfaEnrolment } from '../api/types';
import { Icon } from '../ui/Icon';
import { ErrorText, Loading } from '../ui/components';

/**
 * Second sign-in step with an authenticator app (TOTP). On first sign-in the secret is shown once as a QR code and as
 * a key to type in; the first valid code confirms enrolment.
 */
export function MfaStep({
  enrolled,
  startEnrolment,
  submit,
  onCancel,
}: {
  enrolled: boolean;
  startEnrolment: () => Promise<MfaEnrolment>;
  submit: (code: string) => Promise<unknown>;
  onCancel: () => void;
}) {
  const [enrolment, setEnrolment] = useState<MfaEnrolment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Started once per mount: every call issues a new secret, so it must not follow re-renders.
  const start = useRef(startEnrolment);
  // React may run the effect twice in development; both runs must share one request so the secret shown is the one
  // the server stored.
  const pending = useRef<Promise<MfaEnrolment> | null>(null);

  useEffect(() => {
    if (enrolled) return;
    let cancelled = false;
    pending.current ??= start.current();
    pending.current
      .then(async (e) => {
        if (cancelled) return;
        setEnrolment(e);
        try {
          setQr(await QRCode.toDataURL(e.otpauthUri, { margin: 1, width: 184, errorCorrectionLevel: 'M' }));
        } catch {
          setQr(null);
        }
      })
      .catch(() => !cancelled && setError('Enrolment could not be started. Sign in again.'));
    return () => {
      cancelled = true;
    };
  }, [enrolled]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await submit(code.trim());
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === 'RATE_LIMITED'
          ? 'Too many attempts. Wait a few minutes and sign in again.'
          : 'That code is not valid. Use the current code from your authenticator app.',
      );
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="stat-icon" style={{ width: 40, height: 40, marginBottom: '1rem' }}>
        <Icon name="shieldCheck" size={22} />
      </div>
      {enrolled ? (
        <>
          <h1>Two-step verification</h1>
          <p className="lead">Enter the 6-digit code from your authenticator app.</p>
        </>
      ) : (
        <>
          <h1>Set up two-step verification</h1>
          <p className="lead">
            Organisation Admins protect their account with an authenticator app (for example Microsoft Authenticator,
            Google Authenticator or 1Password). Scan the code, then enter the 6 digits it shows.
          </p>
          {!enrolment && !error && <Loading label="Preparing…" />}
          {enrolment && (
            <>
              {qr && (
                <div className="qr">
                  <img src={qr} alt="QR code for your authenticator app" width={184} height={184} />
                </div>
              )}
              <p className="small">Can&apos;t scan it? Enter this key manually:</p>
              <code className="secret-key" aria-label="Setup key">
                {enrolment.secret.replace(/(.{4})/g, '$1 ').trim()}
              </code>
              <hr />
            </>
          )}
        </>
      )}
      <form onSubmit={(e) => void handleSubmit(e)} noValidate>
        <label>
          Authentication code
          <input
            className="code-input"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            aria-invalid={!!error}
            autoFocus
          />
        </label>
        {error && <ErrorText>{error}</ErrorText>}
        <button type="submit" disabled={busy || code.length !== 6 || (!enrolled && !enrolment)}>
          {busy ? 'Verifying…' : enrolled ? 'Verify' : 'Confirm and continue'}
        </button>
      </form>
      <div className="auth-links">
        <button type="button" className="btn-ghost btn-sm" onClick={onCancel}>
          <Icon name="arrowLeft" size={15} />
          Use another account
        </button>
      </div>
    </div>
  );
}
