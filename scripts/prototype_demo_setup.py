#!/usr/bin/env python3
"""Creates the staff accounts a local prototype walkthrough needs. LOCAL DEVELOPMENT ONLY.

It uses only the platform's real paths, exactly as an operator would by hand:
  * the one-shot ``provision`` process for accounts, the development-only administrator bootstrap, two-person
    capability grants and tenant assignments (each proven with both administrators' TOTP codes);
  * the staff sign-in API for each account's own TOTP enrolment.
Nothing is written to the database directly and no check is bypassed.

Because every administrator code can be used once and a new one appears every 30 seconds, the script waits between
provisioning commands; a full run takes about three minutes.

Accounts (organisation "acme" from the development seed):
  admin-a@prototype.test, admin-b@prototype.test   staff administrators (bootstrap pair)
  reviewer@prototype.test                          REPOSITORY_REGISTER, BUSINESS_REVIEWER, BUSINESS_ANALYST, JOB_RETRY
  release@prototype.test                           RELEASE_APPROVE (must differ from the reviewer: separation of duties)

Passwords and TOTP secrets are random, generated here, and written to .prototype-demo/credentials.json (mode 0600,
git-ignored). Use scripts/totp.py to print a current code, or add the secrets to an authenticator app.

Requires Python 3.9+ (standard library only) and either Docker Compose or SDLC_PROVISION_CMD.

Environment:
  SDLC_BASE_URL       web or API base URL (default http://localhost:8080)
  SDLC_PROVISION_CMD  command that runs the provision process (default: docker compose run --rm -T provision)
  SDLC_DEMO_TENANT    organisation slug to assign the staff to (default acme)
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import http.cookiejar
import json
import os
import secrets
import shlex
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = os.environ.get("SDLC_BASE_URL", "http://localhost:8080").rstrip("/")
PROVISION = shlex.split(os.environ.get("SDLC_PROVISION_CMD", "docker compose run --rm -T provision"))
TENANT = os.environ.get("SDLC_DEMO_TENANT", "acme")
ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / ".prototype-demo"
OUT_FILE = OUT_DIR / "credentials.json"
STEP = 30

ACCOUNTS = {
    "admin-a@prototype.test": {"name": "Prototype Admin A", "capabilities": []},
    "admin-b@prototype.test": {"name": "Prototype Admin B", "capabilities": []},
    "reviewer@prototype.test": {
        "name": "Prototype Reviewer",
        "capabilities": ["REPOSITORY_REGISTER", "BUSINESS_REVIEWER", "BUSINESS_ANALYST", "JOB_RETRY"],
    },
    "release@prototype.test": {"name": "Prototype Release Manager", "capabilities": ["RELEASE_APPROVE"]},
}
ADMIN_A, ADMIN_B = "admin-a@prototype.test", "admin-b@prototype.test"


def totp(secret_b32: str, step: int) -> str:
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


class Codes:
    """Hands out each account's next unused TOTP step, waiting when the server would not accept it yet."""

    def __init__(self) -> None:
        self.last: dict[str, int] = {}

    def next(self, email: str, secret: str) -> str:
        now = int(time.time()) // STEP
        step = max(self.last.get(email, 0) + 1, now - 1)
        if step > now + 1:  # the server accepts the current step and one either side
            wait = (step - 1) * STEP - time.time() + 1
            print(f"    waiting {wait:.0f}s for the next TOTP code of {email} ...", flush=True)
            time.sleep(max(wait, 0))
        self.last[email] = step
        return totp(secret, step)


class Session:
    """A browser-like session: cookie jar plus the CSRF header the API requires."""

    def __init__(self) -> None:
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.call("GET", "/api/v1/auth/csrf")

    def call(self, method: str, path: str, body: dict | None = None) -> dict:
        headers = {"Accept": "application/json"}
        token = next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), None)
        if token and method != "GET":
            headers["X-CSRF-Token"] = token
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE_URL + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")[:300]
            raise SystemExit(f"{method} {path} failed with HTTP {error.code}: {detail}") from None
        except urllib.error.URLError as error:
            raise SystemExit(f"Cannot reach {BASE_URL}: {error.reason}. Is the stack running?") from None
        return json.loads(raw) if raw else {}


def provision(args: list[str], stdin: str | None = None) -> None:
    result = subprocess.run(PROVISION + args, input=stdin, text=True, capture_output=True, cwd=ROOT)
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).strip().splitlines()[-6:])
        shown = " ".join(a if "-code=" not in a else a.split("=")[0] + "=******" for a in args)
        raise SystemExit(f"provision {shown} failed (exit {result.returncode}):\n{tail}")


def main() -> None:
    if OUT_FILE.exists():
        raise SystemExit(f"{OUT_FILE} already exists: the demo accounts were created before.\n"
                         "To start again, reset the stack (docker compose down -v) and delete that file.")
    OUT_DIR.mkdir(mode=0o700, exist_ok=True)
    codes = Codes()
    accounts: dict[str, dict] = {}

    print("1/4 Creating staff accounts (provision create-staff)")
    for email, spec in ACCOUNTS.items():
        password = secrets.token_urlsafe(18)
        provision(["create-staff", f"--email={email}", f"--display-name={spec['name']}"], stdin=password + "\n")
        accounts[email] = {"password": password, "capabilities": spec["capabilities"]}
        print(f"    {email}")

    print("2/4 Enrolling an authenticator for each account (staff sign-in API)")
    for email, account in accounts.items():
        session = Session()
        status = session.call("POST", "/api/v1/staff/auth/login", {"email": email, "password": account["password"]})
        if status.get("status") != "MFA_ENROLMENT_REQUIRED":
            raise SystemExit(f"Unexpected sign-in status for {email}: {status}")
        enrolment = session.call("POST", "/api/v1/staff/auth/mfa/enrolment")
        account["totpSecret"] = enrolment["secret"]
        account["otpauthUri"] = enrolment["otpauthUri"]
        session.call("POST", "/api/v1/staff/auth/mfa/enrolment/confirm", {"code": codes.next(email, enrolment["secret"])})
        print(f"    {email}")
    # Saved before the slow part, so the secrets are not lost if a later step fails.
    OUT_FILE.touch(mode=0o600)
    OUT_FILE.write_text(json.dumps({"baseUrl": BASE_URL, "tenant": TENANT, "accounts": accounts}, indent=2) + "\n")

    def admin_codes(first_flag: str, second_flag: str) -> list[str]:
        return [f"--{first_flag}={ADMIN_A}", f"--{first_flag}-code={codes.next(ADMIN_A, accounts[ADMIN_A]['totpSecret'])}",
                f"--{second_flag}={ADMIN_B}", f"--{second_flag}-code={codes.next(ADMIN_B, accounts[ADMIN_B]['totpSecret'])}"]

    print("3/4 Bootstrapping the two administrators (development environments only)")
    provision(["bootstrap-admins"] + admin_codes("first", "second"))

    print("4/4 Two-person grants and tenant assignments")
    for email, account in accounts.items():
        if not account["capabilities"]:
            continue
        provision(["assign-tenant", f"--staff={email}", f"--tenant={TENANT}"] + admin_codes("requester", "approver"))
        print(f"    {email}: assigned to {TENANT}")
        for capability in account["capabilities"]:
            provision(["grant-capability", f"--staff={email}", f"--capability={capability}"]
                      + admin_codes("requester", "approver"))
            print(f"    {email}: {capability}")

    print(f"\nDone. Credentials: {OUT_FILE}")
    print("Staff sign-in: " + BASE_URL + "/staff/login")
    print("Current code:  python3 scripts/totp.py reviewer@prototype.test")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
