# Garuda: Developer agent (GD-36)

Status: **design agreed, being built** (2026-11-21).

Garuda's own coding agent writes the code for an approved, pushed delivery plan in agent delivery mode (GD-35), one
pull request per story, wave by wave. People review and merge in GitHub; Garuda never merges and never writes to a
default branch.

## Owner decisions (2026-11-21)

1. **Option A:** Garuda's own Developer agent (not a hand-off to an external coding agent).
2. **Fully automatic:** development starts when the approved plan has been pushed to GitHub Issues; each next wave
   starts on its own once every story of the previous wave is merged (and the outside work it needs is closed).
3. **Merge:** always a person, in GitHub. Garuda pushes only to its own `garuda/...` branches.
4. **Sandbox network:** package registries only (Maven Central, Gradle plugins, npm, PyPI). No GitHub credentials,
   no client systems, no general internet inside the sandbox.
5. **Toolchains first:** Java 21 with Maven and Gradle, Node 22 with npm and pnpm, Python 3.12 with pip.

## Components

| Component | Where | Does |
|---|---|---|
| **devbox** (sandbox) | New container, its own image, on an internal network only | Holds one workspace per story: the repository files at the base commit. Lists, reads, searches and writes files; runs build and test commands as an unprivileged user with time, output and resource limits; returns the changed files. Never holds a credential. |
| **egress proxy** | New container | The devbox's only way out: an allowlist of package-registry hosts. |
| **GitHub broker** (extended) | Worker | A token narrowed to the one repository with Contents: write, Pull requests: write, Issues: read, Metadata: read. Creates a commit through the Git data API on a new `refs/heads/garuda/<requirement>/<story>` branch (any other ref is refused), opens a pull request that closes the story's issue, and reads pull request and issue state. |
| **Developer agent** | Worker job `DEV_STORY` | Gives the model the story, its acceptance criteria, the design and the repository tools; the model explores, edits and runs the tests in the devbox until the tests pass or it gives up; Garuda checks the change and opens the pull request. |
| **Orchestrator** | Worker job `DEV_TICK` (periodic) | Starts development after a successful push, reads pull request and issue state, starts the next wave (up to the plan's number of agents at once), marks the requirement delivered when every story is merged. |
| **Development card** | Requirement page | Waves and stories with their status (waiting, coding, pull request open, merged, failed), links, the agent's summary and test result; pause, resume, retry and skip. |

## Safety rules

- Client code runs only in the devbox: non-root, no credentials, registry-only network, CPU, memory, time and output
  limits, one workspace per story, deleted afterwards.
- The model's tools act only on its story's workspace: paths are checked (no `..`, no absolute paths, no symlinks out),
  file and output sizes are capped, commands run without a shell interpreter of the model's choosing (argument list,
  allowlisted programs: the build tools, test runners, `git diff`/`status`, `ls`, `cat`, `grep`-like search).
- Garuda checks the change before committing: size limits, no binary blobs over a cap, and **no changes to
  `.github/workflows` or other CI configuration** (a pull request branch in the same repository can run workflows
  with the repository's secrets).
- The model never sees a GitHub token; the broker writes only `garuda/...` branches and pull requests.
- Every step is on the requirement's timeline and in the audit; model usage is costed as for the other agents.

## Limits of a first version

- Stories are sized for one agent task (GD-35 flags more than 16 agent hours). Very large repositories are explored
  through search and partial reads, not loaded whole.
- The tool loop is bounded (rounds, calls, tokens per story); a story that does not converge fails with its log and
  can be retried or done by a person.
- Quality depends on the configured model; every pull request is reviewed by a person before merge.

## Data (new migration)

`dev_run` (one per requirement and plan version: status RUNNING, PAUSED, DELIVERED, FAILED), `dev_task` (one per
story: wave, status QUEUED, CODING, PR_OPEN, MERGED, FAILED, SKIPPED, branch, pull request number and URL, the
agent's summary and test result, failure code), job types `DEV_STORY` and `DEV_TICK`, prompt `dev.story` v1.
