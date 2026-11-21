"""Garuda devbox (GD-36): the sandbox where the Developer agent's workspaces live and client code is built and tested.

One workspace per story: the repository files at the base commit, extracted from the tarball the worker uploads. The
service lists, reads, searches and writes files inside a workspace, runs allowlisted build and test programs as this
unprivileged user with time and output limits, and returns the changed files. It never holds a credential; the
container reaches the network only through the registry-only egress proxy.

Every request needs the shared token (DEVBOX_TOKEN). Paths from callers are untrusted: they must be relative, stay
inside the workspace and not pass through a symbolic link.
"""

import base64
import hmac
import io
import json
import os
import re
import resource
import shutil
import signal
import subprocess
import tarfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.environ.get("DEVBOX_ROOT", "/workspaces")
TOKEN = os.environ.get("DEVBOX_TOKEN", "")
PORT = int(os.environ.get("DEVBOX_PORT", "8090"))
MAX_UPLOAD = int(os.environ.get("DEVBOX_MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))
MAX_EXTRACTED = int(os.environ.get("DEVBOX_MAX_EXTRACTED_BYTES", str(1024 * 1024 * 1024)))
MAX_FILES = int(os.environ.get("DEVBOX_MAX_FILES", "60000"))
MAX_WRITE = 1024 * 1024
MAX_READ = 64 * 1024
MAX_OUTPUT = 24 * 1024
MAX_EXEC_SECONDS = int(os.environ.get("DEVBOX_MAX_EXEC_SECONDS", "900"))
WORKSPACE_TTL = int(os.environ.get("DEVBOX_WORKSPACE_TTL_SECONDS", str(8 * 3600)))
MAX_CHANGED_BYTES = 20 * 1024 * 1024

# Programs the agent may run (argv[0]); a workspace wrapper (./mvnw, ./gradlew) is allowed when it is in the workspace.
PROGRAMS = {
    "mvn", "gradle", "npm", "npx", "pnpm", "yarn", "node", "python3", "python", "pip", "pip3", "pytest",
    "java", "javac", "git", "ls", "cat", "head", "tail", "wc", "find", "grep", "diff", "make", "tsc", "jest", "vitest",
}
WRAPPERS = {"./mvnw", "./gradlew"}
GIT_SUBCOMMANDS = {"status", "diff", "log", "show", "ls-files"}
# Generated output never belongs in a change, whatever the repository's .gitignore says.
DEFAULT_EXCLUDES = [
    "target/", "build/", "dist/", "out/", "node_modules/", ".gradle/", ".m2/", "__pycache__/", ".venv/", "venv/",
    ".pytest_cache/", ".next/", "coverage/", ".idea/", ".vscode/", "*.class", "*.pyc", ".DS_Store",
]
ID = re.compile(r"^[0-9a-f]{32}$")
_locks = {}
_locks_guard = threading.Lock()


class Refused(Exception):
    """A request the service will not carry out; the message is fixed text."""

    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def lock_for(workspace_id):
    with _locks_guard:
        return _locks.setdefault(workspace_id, threading.Lock())


def workspace_dir(workspace_id):
    if not ID.match(workspace_id or ""):
        raise Refused(404, "WORKSPACE_NOT_FOUND")
    path = os.path.join(ROOT, workspace_id, "repo")
    if not os.path.isdir(path):
        raise Refused(404, "WORKSPACE_NOT_FOUND")
    return path


def resolve(base, relative, must_exist=False):
    """A path inside the workspace: relative, no '..', no symbolic link on the way."""
    if not isinstance(relative, str) or len(relative) > 1000 or "\x00" in relative:
        raise Refused(400, "PATH_INVALID")
    relative = relative.strip()
    while relative.startswith("./"):
        relative = relative[2:]
    if relative == ".":
        relative = ""
    if relative.startswith("/") or any(part == ".." for part in relative.split("/")):
        raise Refused(400, "PATH_INVALID")
    if relative == ".git" or relative.startswith(".git/"):
        raise Refused(400, "PATH_INVALID")
    current = base
    for part in [p for p in relative.split("/") if p]:
        current = os.path.join(current, part)
        if os.path.islink(current):
            raise Refused(400, "PATH_INVALID")
    if must_exist and not os.path.exists(current):
        raise Refused(404, "PATH_NOT_FOUND")
    real_base = os.path.realpath(base)
    if os.path.commonpath([real_base, os.path.realpath(current)]) != real_base:
        raise Refused(400, "PATH_INVALID")
    return current


def git(base, *args, timeout=120):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.path.dirname(base), "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_TERMINAL_PROMPT": "0", "LANG": "C.UTF-8"}
    return subprocess.run(["git", *args], cwd=base, env=env, capture_output=True, timeout=timeout, check=False)


def create_workspace(body):
    if len(body) > MAX_UPLOAD:
        raise Refused(413, "UPLOAD_TOO_LARGE")
    workspace_id = uuid.uuid4().hex
    home = os.path.join(ROOT, workspace_id)
    repo = os.path.join(home, "repo")
    os.makedirs(repo)
    try:
        extract(body, repo)
        with open(os.path.join(home, "created"), "w", encoding="utf-8") as f:
            f.write(str(time.time()))
        for args in (("init", "-q"), ("config", "user.email", "garuda-developer@localhost"),
                     ("config", "user.name", "Garuda Developer agent"), ("config", "core.autocrlf", "false")):
            git(repo, *args)
        with open(os.path.join(repo, ".git", "info", "exclude"), "a", encoding="utf-8") as f:
            f.write("\n".join(DEFAULT_EXCLUDES) + "\n")
        git(repo, "add", "-A", timeout=600)
        result = git(repo, "commit", "-q", "--allow-empty", "-m", "base", timeout=600)
        if result.returncode != 0:
            raise Refused(500, "WORKSPACE_BASE_FAILED")
    except Exception:
        shutil.rmtree(home, ignore_errors=True)
        raise
    return {"id": workspace_id}


def extract(body, repo):
    """A GitHub tarball: one top-level directory, regular files and directories only."""
    total = 0
    count = 0
    try:
        archive = tarfile.open(fileobj=io.BytesIO(body), mode="r:*")
    except tarfile.TarError:
        raise Refused(400, "ARCHIVE_INVALID")
    with archive:
        for member in archive:
            if member.name == "pax_global_header" or member.type in (tarfile.XGLTYPE, tarfile.XHDTYPE):
                continue
            parts = [p for p in member.name.split("/") if p not in ("", ".")]
            if len(parts) < 2:
                continue  # the top-level directory itself
            relative = "/".join(parts[1:])
            if member.name.startswith("/") or ".." in parts or parts[1] == ".git":
                raise Refused(400, "ARCHIVE_INVALID")
            target = os.path.join(repo, relative)
            if member.isdir():
                os.makedirs(target, exist_ok=True)
                continue
            if not member.isfile():
                continue  # links, devices and fifos are not extracted
            count += 1
            total += member.size
            if count > MAX_FILES or total > MAX_EXTRACTED:
                raise Refused(413, "ARCHIVE_TOO_LARGE")
            os.makedirs(os.path.dirname(target), exist_ok=True)
            source = archive.extractfile(member)
            with open(target, "wb") as out:
                shutil.copyfileobj(source, out)
            os.chmod(target, 0o755 if member.mode & 0o111 else 0o644)


def list_dir(base, body):
    path = resolve(base, body.get("path", ""), must_exist=True)
    depth = max(1, min(int(body.get("depth", 2)), 4))
    entries = []
    root_len = len(base.rstrip("/")) + 1
    for current, dirs, files in os.walk(path):
        level = current[len(path):].count(os.sep)
        dirs[:] = sorted(d for d in dirs if d not in (".git", "node_modules", "target", "build", ".gradle", "dist",
                                                      "__pycache__", ".venv", ".next"))
        for d in dirs:
            entries.append(os.path.join(current, d)[root_len:] + "/")
        for f in sorted(files):
            full = os.path.join(current, f)
            if not os.path.islink(full):
                entries.append(full[root_len:])
        if level + 1 >= depth:
            dirs[:] = []
        if len(entries) > 2000:
            break
    return {"entries": entries[:2000], "truncated": len(entries) > 2000}


def read_file(base, body):
    path = resolve(base, body.get("path", ""), must_exist=True)
    if not os.path.isfile(path):
        raise Refused(400, "NOT_A_FILE")
    start = max(1, int(body.get("startLine", 1)))
    limit = max(1, min(int(body.get("maxLines", 400)), 2000))
    out = []
    size = 0
    total = 0
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for number, line in enumerate(f, 1):
            total = number
            if number < start or len(out) >= limit or size > MAX_READ:
                continue
            out.append(line)
            size += len(line)
    return {"content": "".join(out), "startLine": start, "lines": len(out), "totalLines": total}


def write_file(base, body):
    content = body.get("content")
    if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_WRITE:
        raise Refused(400, "CONTENT_INVALID")
    path = resolve(base, body.get("path", ""))
    if os.path.isdir(path):
        raise Refused(400, "NOT_A_FILE")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    resolve(base, os.path.relpath(os.path.dirname(path), base))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)
    return {"written": len(content)}


def delete_file(base, body):
    path = resolve(base, body.get("path", ""), must_exist=True)
    if not os.path.isfile(path):
        raise Refused(400, "NOT_A_FILE")
    os.remove(path)
    return {"deleted": True}


def search(base, body):
    pattern = body.get("pattern")
    if not isinstance(pattern, str) or not 1 <= len(pattern) <= 200:
        raise Refused(400, "PATTERN_INVALID")
    try:
        regex = re.compile(pattern if body.get("regex") else re.escape(pattern),
                           0 if body.get("caseSensitive") else re.IGNORECASE)
    except re.error:
        raise Refused(400, "PATTERN_INVALID")
    path = resolve(base, body.get("path", ""), must_exist=True)
    matches = []
    root_len = len(base.rstrip("/")) + 1
    for current, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if d not in (".git", "node_modules", "target", "build", ".gradle", "dist",
                                                "__pycache__", ".venv", ".next")]
        for f in files:
            full = os.path.join(current, f)
            if os.path.islink(full) or os.path.getsize(full) > 1024 * 1024:
                continue
            try:
                with open(full, "r", encoding="utf-8") as handle:
                    for number, line in enumerate(handle, 1):
                        if regex.search(line):
                            matches.append({"path": full[root_len:], "line": number, "text": line.rstrip()[:300]})
                            if len(matches) >= 200:
                                return {"matches": matches, "truncated": True}
            except (UnicodeDecodeError, OSError):
                continue
    return {"matches": matches, "truncated": False}


def run(base, body):
    argv = body.get("argv")
    if not isinstance(argv, list) or not argv or len(argv) > 40 or not all(
            isinstance(a, str) and len(a) <= 500 and "\x00" not in a for a in argv):
        raise Refused(400, "COMMAND_INVALID")
    program = argv[0]
    if program in WRAPPERS:
        resolve(base, program[2:], must_exist=True)
    elif program not in PROGRAMS:
        raise Refused(400, "COMMAND_NOT_ALLOWED")
    if program == "git" and (len(argv) < 2 or argv[1] not in GIT_SUBCOMMANDS):
        raise Refused(400, "COMMAND_NOT_ALLOWED")
    cwd = resolve(base, body.get("cwd", ""), must_exist=True)
    seconds = max(1, min(int(body.get("timeoutSeconds", 600)), MAX_EXEC_SECONDS))
    home = os.path.join(os.path.dirname(base), "home")
    os.makedirs(home, exist_ok=True)
    env = {
        "PATH": os.environ.get("PATH", ""), "HOME": home, "LANG": "C.UTF-8", "CI": "true",
        "JAVA_HOME": os.environ.get("JAVA_HOME", ""), "MAVEN_OPTS": os.environ.get("MAVEN_OPTS", ""),
        "GRADLE_OPTS": os.environ.get("GRADLE_OPTS", ""), "JAVA_TOOL_OPTIONS": os.environ.get("JAVA_TOOL_OPTIONS", ""),
        "HTTP_PROXY": os.environ.get("HTTP_PROXY", ""), "HTTPS_PROXY": os.environ.get("HTTPS_PROXY", ""),
        "http_proxy": os.environ.get("HTTP_PROXY", ""), "https_proxy": os.environ.get("HTTPS_PROXY", ""),
        "NO_PROXY": "localhost,127.0.0.1", "npm_config_update_notifier": "false", "PIP_DISABLE_PIP_VERSION_CHECK": "1",
    }
    env = {k: v for k, v in env.items() if v}

    def limits():
        os.setsid()
        resource.setrlimit(resource.RLIMIT_CPU, (seconds * 4, seconds * 4))
        resource.setrlimit(resource.RLIMIT_FSIZE, (512 * 1024 * 1024, 512 * 1024 * 1024))

    started = time.time()
    process = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, preexec_fn=limits)
    timed_out = False
    try:
        output, _ = process.communicate(timeout=seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        output, _ = process.communicate()
    text = output.decode("utf-8", errors="replace")
    truncated = len(text) > MAX_OUTPUT
    if truncated:
        # The end of a build log says what failed; keep the start too for context.
        text = text[:4096] + "\n[... output cut ...]\n" + text[-(MAX_OUTPUT - 4096):]
    return {"exitCode": process.returncode, "timedOut": timed_out, "seconds": round(time.time() - started, 1),
            "output": text, "truncated": truncated}


def changes(base, _body):
    git(base, "add", "-A", timeout=600)
    result = git(base, "diff", "--cached", "--name-status", "-z", "--no-renames", "HEAD", timeout=600)
    if result.returncode != 0:
        raise Refused(500, "DIFF_FAILED")
    fields = result.stdout.decode("utf-8", errors="replace").split("\x00")
    files = []
    total = 0
    for i in range(0, len(fields) - 1, 2):
        status, path = fields[i], fields[i + 1]
        entry = {"path": path, "status": {"A": "added", "M": "modified", "D": "deleted"}.get(status[:1], "modified")}
        if entry["status"] != "deleted":
            full = os.path.join(base, path)
            if os.path.islink(full) or not os.path.isfile(full):
                continue
            with open(full, "rb") as f:
                data = f.read()
            total += len(data)
            if total > MAX_CHANGED_BYTES:
                raise Refused(413, "CHANGE_TOO_LARGE")
            entry["content"] = base64.b64encode(data).decode("ascii")
            entry["executable"] = bool(os.stat(full).st_mode & 0o111)
        files.append(entry)
    stat = git(base, "diff", "--cached", "--stat", "HEAD").stdout.decode("utf-8", errors="replace")
    git(base, "reset", "-q")
    return {"files": files, "stat": stat[-4000:]}


def delete_workspace(workspace_id):
    if not ID.match(workspace_id or ""):
        raise Refused(404, "WORKSPACE_NOT_FOUND")
    shutil.rmtree(os.path.join(ROOT, workspace_id), ignore_errors=True)
    return {"deleted": True}


def sweep():
    """Workspaces older than the TTL are removed (a crashed job leaves none behind for long)."""
    while True:
        time.sleep(600)
        now = time.time()
        for name in os.listdir(ROOT):
            stamp = os.path.join(ROOT, name, "created")
            try:
                with open(stamp, encoding="utf-8") as f:
                    created = float(f.read().strip())
            except (OSError, ValueError):
                continue
            if now - created > WORKSPACE_TTL:
                shutil.rmtree(os.path.join(ROOT, name), ignore_errors=True)


ACTIONS = {"list": list_dir, "read": read_file, "write": write_file, "delete": delete_file, "search": search,
           "exec": run, "changes": changes}


class Handler(BaseHTTPRequestHandler):
    server_version = "garuda-devbox"
    sys_version = ""

    def log_message(self, fmt, *args):  # request lines only, never bodies
        print("devbox %s %s" % (self.command, self.path.split("?")[0]), flush=True)

    def _authorised(self):
        header = self.headers.get("Authorization", "")
        return bool(TOKEN) and hmac.compare_digest(header, "Bearer " + TOKEN)

    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self, limit):
        length = int(self.headers.get("Content-Length", "0"))
        if length > limit:
            raise Refused(413, "BODY_TOO_LARGE")
        return self.rfile.read(length) if length else b""

    def _handle(self, method):
        if self.path == "/health" and method == "GET":
            return self._send(200, {"status": "UP"})
        if not self._authorised():
            return self._send(401, {"code": "UNAUTHORISED"})
        try:
            parts = [p for p in self.path.split("?")[0].split("/") if p]
            if parts == ["v1", "workspaces"] and method == "POST":
                return self._send(201, create_workspace(self._body(MAX_UPLOAD)))
            if len(parts) == 3 and parts[:2] == ["v1", "workspaces"] and method == "DELETE":
                return self._send(200, delete_workspace(parts[2]))
            if len(parts) == 4 and parts[:2] == ["v1", "workspaces"] and method == "POST" and parts[3] in ACTIONS:
                base = workspace_dir(parts[2])
                raw = self._body(2 * MAX_WRITE)
                try:
                    body = json.loads(raw or b"{}")
                except ValueError:
                    raise Refused(400, "BODY_INVALID")
                if not isinstance(body, dict):
                    raise Refused(400, "BODY_INVALID")
                with lock_for(parts[2]):
                    return self._send(200, ACTIONS[parts[3]](base, body))
            return self._send(404, {"code": "NOT_FOUND"})
        except Refused as refused:
            return self._send(refused.status, {"code": refused.code})
        except (ValueError, TypeError):
            return self._send(400, {"code": "BODY_INVALID"})
        except Exception as error:  # never leak details
            print("devbox error %s" % type(error).__name__, flush=True)
            return self._send(500, {"code": "INTERNAL"})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_DELETE(self):
        self._handle("DELETE")


def main():
    if len(TOKEN) < 24:
        raise SystemExit("DEVBOX_TOKEN must be set (at least 24 characters)")
    os.makedirs(ROOT, exist_ok=True)
    threading.Thread(target=sweep, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("devbox listening on %d" % PORT, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
