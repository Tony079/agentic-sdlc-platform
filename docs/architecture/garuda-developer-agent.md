# Garuda: Developer agent (GD-36)

Status: **implemented** (2026-11-21). Not yet tried against real GitHub and a real model: tested end to end with the real devbox, a fake GitHub and a scripted model.

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
| **GitHub broker** (extended) | Worker | A token narrowed to the one repository with Contents: write, Pull requests: write, Issues: read, Metadata: read. Creates a commit through the Git data API on a new `refs/heads/garuda/<requirement>/<story>` branch (any other ref is refused), opens a pull request (a draft for unfinished work) that closes the story's issue, reads a garuda/ branch head, and reads pull request and issue state. |
| **Developer agent** | Worker job `DEV_STORY` | Gives the model the story, its acceptance criteria, the design and the repository tools; the model explores, edits and runs the tests in the devbox until the tests pass or it gives up; Garuda checks the change and opens the pull request. It works in **sessions** of at most 10 tool rounds (the gateway's limit), up to 8 per story; between sessions it gets its work log and the list of changed files, so each session starts with a small, fresh context. After each session the timeline shows its answer (CONTINUE, DONE, GIVE_UP or no usable answer), the tools it used and the number of files changed. A session that runs out of tool rounds keeps its changes and the next one is told to write and answer sooner; two sessions in a row with no tool use and no new changes stop the story as `AGENT_STALLED` instead of using up all 8. Prompt `dev.story` v2 asks for at most 3 rounds of exploration before writing; v3 says the workspace has no Docker, so container and deployment work is written and delivered as DONE with what a person must still run, rather than given up. **Base branch:** each story starts from, and its pull request goes into, the branch the requirement's environment names for the repository (for example `releases/qc`); the repository's default branch only when the environment names none. **Unfinished work is kept** (owner, 2026-11-21): when a story runs out of sessions, stalls or gives up with changes, Garuda commits them to the story's garuda/ branch and opens a **draft** pull request titled "[Not finished] …" with where it stopped and the work log; the task stays FAILED with the link. **Retry** carries on from the branch head (so commits a person pushed there are kept) and the work log; when the story is done, the same pull request is updated and marked ready for review (GitHub GraphQL `markPullRequestReadyForReview`, same token). If a person finishes the draft and merges it in GitHub, the story counts as merged. On Azure/OpenAI tool calls use the Responses API with a JSON-object answer format. |
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

## Operations

- **GitHub App permissions:** Contents: Read and write, Pull requests: Read and write, Issues: Read and write,
  Metadata: Read. The installation owner must accept the new permissions. Without them a story fails with "the Garuda
  GitHub App has not been granted Contents and Pull requests: Read and write"; grant them, then **Retry**.
- **Recommended in GitHub:** protect the default branch (require a pull request review before merging), so nothing
  reaches it without a person.
- **`.env`:** `SDLC_DEVBOX_TOKEN`, any random string of 32 or more characters (`openssl rand -hex 24`). Docker Compose
  builds and starts `devbox` and `devbox-proxy` with the rest.
- **Registries:** to allow another package registry (for example a company mirror), add its host to
  `devbox-proxy/allowlist` and rebuild `devbox-proxy`.
- **Starting development for a plan pushed before this release:** push the plan again; development starts after a
  successful push of an approved plan made for coding agents.
