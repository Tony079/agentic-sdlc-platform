# Garuda: onboarding organisations and account email

Owner decisions GD-03, GD-04 and GD-08 (decision register §15). This runbook covers the operator steps. The model is described in `docs/architecture/garuda-platform-model.md`.

## 1. Garuda staff

**Once per deployment (command line):** create the first two staff administrators with the two-person bootstrap (`create-staff` for each, authenticator set-up at `/staff/login`, then `bootstrap-admins` or the production ceremony).

**Everyone else (console only, GD-15):** a staff administrator opens **Console → Team → Invite staff member**, enters the name and email, and ticks capabilities. A different staff administrator approves the request in **Team → Requests**. The new person:
1. receives a single-use link (emailed, and also shown to the approver);
2. sets a password;
3. sets up an authenticator at first sign-in.

The same screen handles every later change. Each is approved by a second administrator, and nobody can change their own account:
- grant or revoke capabilities;
- assign or remove organisations;
- disable or reactivate someone;
- reset an authenticator or a password.

| Capability | Gives access to |
|---|---|
| `STAFF_ADMIN` | The Team screen, plus the dashboard |
| `ORG_ONBOARD` | Onboarding and status requests, plus the dashboard |
| `AUDITOR` | The dashboard only |

Onboarding an organisation needs two people who both hold `ORG_ONBOARD` (one requests, the other approves).

## 2. Onboarding flow

1. Staff member A opens **Console → Onboarding & status → Onboard organisation** and enters:
   - the organisation name;
   - its sign-in code (used in `/o/{code}/login`);
   - the first Organisation Admin's name and email.
2. Staff member B approves. In one transaction Garuda creates the organisation and the invited admin. The admin's invitation link is:
   - emailed, when SMTP is configured;
   - always shown to B, to copy and send.
3. The admin opens the link, chooses a password and signs in. They enrol an authenticator app at that first sign-in (GD-08, mandatory in production).
4. The admin creates projects and invites people. Each person is invited straight into projects with a role: Requester, Approver or Viewer.

Invitation and reset links:
- work once;
- expire after 72 hours (a self-service reset link after 1 hour);
- are stored only as a SHA-256 hash;
- carry the token in the URL fragment, which browsers never send to a server.

## 3. Email through Gmail (free)

Gmail sends account email over SMTPS with an App Password. Gmail limits daily sending: about 500 messages a day for a personal account.

1. Use a dedicated Google account, for example `garuda.notifications@gmail.com`. Turn on 2-Step Verification.
2. Create an App Password: Google Account → Security → App passwords. Copy the 16 characters.
3. Set these in `.env` (never commit them):

   ```
   SDLC_PUBLIC_BASE_URL=https://garuda.example.com
   SDLC_MAIL_HOST=smtp.gmail.com
   SDLC_MAIL_PORT=465
   SDLC_MAIL_USERNAME=garuda.notifications@gmail.com
   SDLC_MAIL_FROM=garuda.notifications@gmail.com
   SDLC_MAIL_APP_PASSWORD=<app password>
   ```

   In production the password is the secret `mail.smtp-password` from the secrets service, not an environment variable.
4. Restart the `api` service. **Organisation → Security** shows "Invitation email: Sent by Garuda".

If a message cannot be delivered, the admin sees "Email failed" and copies the link instead. Message content and recipients are never logged.

Without `SDLC_MAIL_HOST`, Garuda uses copy-link only. Self-service "forgot password" then sends nothing, and an Organisation Admin issues reset links instead.

## 4. Production guards

The api refuses to start in production in either of these cases:
- `sdlc.security.org-admin-mfa-required=false` (`ORG_ADMIN_MFA_DISABLED`);
- `SDLC_PUBLIC_BASE_URL` is not `https://` (`PUBLIC_BASE_URL_NOT_HTTPS`).
