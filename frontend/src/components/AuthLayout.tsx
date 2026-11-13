import type { ReactNode } from 'react';
import { GarudaMark, Icon } from '../ui/Icon';

/** Split-screen frame for sign-in, invitation and password screens. */
export function AuthLayout({ children, variant = 'client' }: { children: ReactNode; variant?: 'client' | 'staff' }) {
  return (
    <div className="auth">
      <aside className="auth-art" aria-hidden="true">
        <div className="brand">
          <GarudaMark size={34} />
          <span>GARUDA</span>
        </div>
        <div>
          {variant === 'client' ? (
            <>
              <h2>From business need to an approved specification.</h2>
              <p>
                Submit requirements, share documents and repositories, answer clarifications and approve the business
                requirements document, all in one secure workspace for your organisation.
              </p>
              <ul className="auth-points">
                <li>
                  <Icon name="shieldCheck" className="icon" />
                  <span>Your organisation&apos;s data is isolated at the database level.</span>
                </li>
                <li>
                  <Icon name="users" className="icon" />
                  <span>Access follows the projects you belong to and your role in each.</span>
                </li>
                <li>
                  <Icon name="activity" className="icon" />
                  <span>Every change and approval is recorded in the audit history.</span>
                </li>
              </ul>
            </>
          ) : (
            <>
              <h2>Garuda operations console.</h2>
              <p>Restricted to Garuda staff. Sign-in requires your password and an authenticator code.</p>
              <ul className="auth-points">
                <li>
                  <Icon name="building" className="icon" />
                  <span>Onboard and oversee client organisations.</span>
                </li>
                <li>
                  <Icon name="checkCircle" className="icon" />
                  <span>Sensitive actions need a second approver.</span>
                </li>
              </ul>
            </>
          )}
        </div>
        <div className="foot">Garuda · Secure requirements platform</div>
      </aside>
      <main className="auth-panel">
        <div className="auth-card">{children}</div>
      </main>
    </div>
  );
}
