# AGENTS.md — working in this repo

**attempt-02** is a rehearsal of the *deployment*, not of banking. Two repos, a submodule,
one compose, a request→`202`→callback loop, and — the part attempt-01 never had — a
push-to-AWS pipeline in `infra/` and `.github/workflows/deploy.yml`. The "business logic"
inside the service is a seeded weighted coin flip, on purpose; don't grow it into real
rules. What is being tested is that a push becomes a running environment and that the
orchestrator can reach the service across the network it finds there.

## Before anything else

This repo has **git submodules**. If `neo-01/` looks empty:

```bash
git submodule update --init
```

Nothing builds until that has run.

## The rules that hold the thing together

1. **The contract is fixed** (`api-contract.md`). A service answers `POST
   /api/v1/applications` with **`202`** `{status:"in-progress", applicationId, serviceId,
   command}` and calls back with exactly four fields `{applicationId, serviceId, status,
   comment}`, `status` ∈ `ACCEPTED · REJECTED · REFERRED`. Every deployed service depends
   on that shape; `CallbackControllerTest` and `ApplicationControllerTest` pin it. If a change
   makes them fail, the change is wrong.
2. **`serviceId` ≠ repo name.** `neo-01` is the repository; `neo01` is what it
   sends on callbacks. Intentional — don't "fix" it.
3. **`application_event` is append-only.** Insert; never update, never delete. Both screens
   and the service summary are *derived* from it, so a wrong fact is corrected by another
   row, not an edit.
4. **Only `ACCEPTED` advances a journey.** Everything else is terminal.
5. **A terminal application never restarts.** `SagaStore.recordCallback` short-circuits on
   `isTerminal()`. Removing that lets a callback arriving after the timeout sweeper
   resurrect a dead journey — there is a test for exactly this.
6. **The saga length is configuration, not code.** `application.yml` declares ten steps and
   must stay that way — `SagaFlowTest` asserts against `neo02` and `neo07`. The
   pilot runs one step because `docker-compose.yml` and the ECS task definition set
   `ORCHESTRATOR_SERVICES_0_*`, which replace the list wholesale. Never "fix" this by
   editing the yml. The check is `GET /api/v1/services` returning one row; CI asserts it.
7. **The front end's path prefix is baked at build time.** Vite writes asset URLs into
   index.html when the image is built, and an ALB cannot rewrite paths, so `APP_BASE_PATH`
   is a Docker build argument. It must equal the slot's `PathPrefix` in
   `infra/env/*.params`. A mismatch serves a blank page with 404s for `/assets/…`.
8. **Schema isolation.** Each service owns one MySQL schema and reads no other.
9. **The generator starts off.** The orchestrator calls services that call it back; firing
   at boot means firing at containers that aren't listening.
10. **`ui-kit/` is shipped to module repos and never edited in a copy.** It is **vendored** —
    copied by `scripts/sync-design-system.sh`, with `--check` gating CI — because app code lives
    inside it. Fix it here; an edited copy is reported and overwritten on the next sync.
11. **The mock orchestrator is NOT in this repo, and must not be moved into it.** It lives in
    `github.com/gjavolce/neobank-sidecar` and module repos build it as a git build context. A git
    context clones submodules recursively, so the `neo-01` submodule's SSH URL makes any
    subdirectory of this repo unusable as one — the failure is a bare
    `Please make sure you have the correct access rights`, which reads like a permissions
    problem and is not.

## Structural gotchas worth knowing

- **Transactions.** `SagaStore` holds every DB write and `SagaEngine` does HTTP and
  scheduling. That split is not decoration: calling a `@Transactional` method from a
  sibling method of the same bean bypasses the proxy entirely, so the two must stay in
  separate beans.
- **`payload_json` is `VARCHAR(8000)`, not `TEXT`/`@Lob`.** H2 and MySQL render those
  differently and `ddl-auto=validate` then refuses to start on one of them. This cost a
  debugging round already; `OrchestratorSchemaIT` now guards it.
- **Dispatch must not block.** `RestClient` has explicit connect/read timeouts — without
  them one unreachable service pins a scheduler thread and stalls every other journey.
- **Migrations are append-only.** Add a Liquibase change set; never edit an applied one.

## Map

| Where | What |
|---|---|
| `backend/.../saga/SagaEngine.java` | the sequencer: dispatch, advance, stop |
| `backend/.../saga/SagaStore.java` | every DB write + the views both screens read |
| `backend/.../saga/TimeoutSweeper.java` | gives up on a silent service |
| `backend/.../generator/` | the toggle, and the seed-42 application factory |
| `backend/.../web/` | the REST surface |
| `frontend/src/components/` | `ApplicationsScreen` · `ServicesScreen` · `TopBar` |
| `frontend/src/status.js` | one place that decides what each status looks like |
| `neo-01/` | the service, a submodule with its own repo and its own CI |
| `ui-kit/` | canonical design system + its spec; vendored into every frontend |
| `infra/` | the AWS environment as CloudFormation — see its README before touching it |

## Tests

```bash
cd backend && ./mvnw test                    # 25, H2, no Docker
cd backend && ./mvnw verify -DskipITs=false  # + 3 real-MySQL (Docker required)
```

`SagaFlowTest` is the one that matters: it drives the state machine with the HTTP call
stubbed and asserts what must *not* happen — no advance past a rejection, no dispatch after
a timeout, no resurrection from a late callback.
