#!/usr/bin/env python3
"""Prints the current TOTP code of a prototype demo staff account. LOCAL DEVELOPMENT ONLY.

Usage: python3 scripts/totp.py reviewer@prototype.test
Reads the secret from .prototype-demo/credentials.json (written by prototype_demo_setup.py). Each code is accepted
once; wait for the next one (30 seconds) before signing in again with the same account.
"""
import base64
import hashlib
import hmac
import json
import struct
import sys
import time
from pathlib import Path

FILE = Path(__file__).resolve().parent.parent / ".prototype-demo" / "credentials.json"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    if not FILE.exists():
        raise SystemExit(f"{FILE} not found. Run scripts/prototype_demo_setup.py first.")
    accounts = json.loads(FILE.read_text())["accounts"]
    account = accounts.get(sys.argv[1])
    if account is None:
        raise SystemExit("Unknown account. Known: " + ", ".join(accounts))
    secret = account["totpSecret"]
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    now = time.time()
    digest = hmac.new(key, struct.pack(">Q", int(now) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    print(f"{code:06d}   (password: {account['password']}; valid for {30 - int(now) % 30}s)")


if __name__ == "__main__":
    main()
