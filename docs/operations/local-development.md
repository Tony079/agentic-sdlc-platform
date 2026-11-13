# Local Development

## Prerequisites
Docker with Compose v2, or for running tests without Docker: JDK 21, Maven 3.9, Node 22, and a PostgreSQL 16 superuser URL.

## Start the stack
1. Create the environment file. Replace **every** placeholder with a random value.
   ```bash
   cp .env.example .env
   ```
   Generate the passwords with `openssl rand -hex 24`, and `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY` with `openssl rand -base64 32`.
2. Start the stack:
   ```bash
   docker compose up --build
   ```
3. Open `http://localhost:${SDLC_WEB_PORT:-8080}`.

Startup order (enforced by `depends_on` and health checks): `db` (healthy), then `migrate` (completes), then `api`,
`worker` and `clamav`, then `web`.

The development seed (`SDLC_DEV_SEED=true`) creates the tenants `acme` and `globex`, each with a Client User and a
Client Approver, all using `SDLC_DEV_SEED_PASSWORD`.

## Staff accounts (no default staff exists)
Run provisioning one command at a time with the on-demand `provision` service (profile `tools`). Passwords are read
from stdin; codes are the current TOTP codes of the named administrators.

1. Create staff member A. It needs a password file of at least 12 characters:
   ```bash
   docker compose run --rm -T provision create-staff --email=a@example.test --display-name="Admin A" < a-password.txt
   ```
2. Repeat step 1 for B and for the working staff member.
3. Each member signs in at `/staff/login` within 72 hours and enrols an authenticator.
4. Bootstrap the first two administrators. This works only in development/test:
   ```bash
   docker compose run --rm provision bootstrap-admins --first=a@example.test --first-code=123456 --second=b@example.test --second-code=654321
   ```
5. Grant capabilities and tenant assignments (two administrators each):
   ```bash
   docker compose run --rm provision grant-capability --staff=s@example.test --capability=REPOSITORY_REGISTER --requester=a@example.test --requester-code=111111 --approver=b@example.test --approver-code=222222
   ```
   ```bash
   docker compose run --rm provision grant-capability --staff=s@example.test --capability=BUSINESS_REVIEWER --requester=a@example.test --requester-code=333333 --approver=b@example.test --approver-code=444444
   ```
   ```bash
   docker compose run --rm provision assign-tenant --staff=s@example.test --tenant=acme --requester=a@example.test --requester-code=555555 --approver=b@example.test --approver-code=666666
   ```

## External services in development
- **Working prototype:** to run the whole workflow without external accounts, use the prototype overlay and the staff setup script described in `prototype-guide.md`. It enables two development adapters (mock model output, bundled sample repository), both labelled in the UI and refused in production mode.
- **GitHub:** off (`SDLC_GITHUB_ENABLED=false`). Without the prototype overlay, registered repositories stay PENDING_VERIFICATION and validation waits. To use a real GitHub App, see `secrets-and-key-management.md`.
- **Model provider:** `SDLC_MODEL_PROVIDER=none` by default, so analysis waits (no data leaves the machine). `development-mock` is the prototype provider; `fake` is for tests only and returns empty output unless a test scripts it.
- **Extraction:** the Compose worker enables it for synthetic documents only (`extraction-isolation.md`).

## Tests without Docker
Run each command on its own:
```bash
export SDLC_TEST_ADMIN_JDBC_URL=jdbc:postgresql://localhost:5432/postgres SDLC_TEST_ADMIN_USER=postgres SDLC_TEST_ADMIN_PASSWORD=...
```
```bash
cd backend && mvn verify
```
```bash
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
```
Without these variables, the backend tests start a `postgres:16` Testcontainer (Docker required).
