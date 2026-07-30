# AGENTS.md — working in this repo

**neo-00 is the platform**, not a module. It owns the journey, the board, the AWS
environment and the shared contract; ten separately-owned module repos plug into it as git
submodules. It is instructor-owned — teams treat it as fixed ground and never send a PR here.

The business logic lives in the *modules*, and each module's is its team's to write. Nothing
in this repo should ever contain a rule about income, sanctions or card limits.

## Before anything else

This repo has **ten git submodules**. If the `neo-NN/` folders look empty:

```bash
git submodule update --init
```

Nothing builds until that has run. You do not need all ten running to work on the
orchestrator — `docker compose up orchestrator frontend-00 neo-01 frontend-01` is enough, and
absent modules simply time out.

## The rules that hold the thing together

1. **The contract is fixed** (`api-contract.md`), and now **ten repos depend on it**. A module
   answers `POST /api/v1/applications` with **`202`** `{status:"in-progress", applicationId,
   serviceId, command}` and reports its outcome with `PUT /api/v1/applications/{id}` carrying
   exactly three fields `{serviceId, status, comment}`, `status` ∈ `ACCEPTED · REJECTED ·
   REFERRED`. `ApplicationControllerTest` pins it. If a change makes it fail, the change is
   wrong — and here that is not a figure of speech: a breaking edit lands on ten teams at once,
   so it has to be made in the orchestrator, the sidecar and the template together.
   Those three are what a module should **send**; the orchestrator also **accepts** each
   module's own brief word (`PASSED`, `CLEAR`, `SIGNED`, `ISSUED`…) and the briefs' canonical
   set, because the briefs predate the simplified contract and a team can reasonably arrive at
   any of them. `StatusVocabulary` owns that table — it widens what is understood without
   changing the wire.
2. **`serviceId` ≠ repo name.** `neo-04` is the repository; `neo04` is what it sends.
   Intentional — don't "fix" it.
3. **`application_event` is append-only.** Insert; never update, never delete. Both screens
   and the service summary are *derived* from it, so a wrong fact is corrected by another
   row, not an edit.
4. **Only a status that resolves to `ACCEPTED` advances a journey** — via `StatusVocabulary`,
   so `PASSED`, `CLEAR`, `SIGNED`, `OPENED`, `ISSUED`, `VERIFIED`, `APPROVED`, `RESOLVED` and
   `COMPLETED` all do. `REJECTED` and `REFERRED` are terminal. `IN_PROGRESS`/`PENDING` are
   neither: they are recorded as a `PROGRESS_REPORTED` event, the journey keeps waiting, and the
   30-second clock restarts. A word in no table is recorded, warned about, and stalls the journey
   until the sweeper fails it.
5. **A terminal application never restarts.** `SagaStore.recordApplicationStatusUpdate`
   short-circuits on `isTerminal()`. Removing that lets an answer arriving after the timeout
   sweeper resurrect a dead journey — there is a test for exactly this.
6. **The journey is configuration, not code.** `application.yml` declares the **eight** steps —
   order, `serviceId`, display name — and is the ONE place the sequence is defined. Nothing
   counts to eight in code: `SagaStore` uses `registry.size()`, so changing that list changes
   the journey's length. `docker-compose.yml` and the ECS task definition override only the
   base URLs. Beware: Spring Boot does not merge collections across property sources, so
   anything supplying `orchestrator.services` supplies **all** of it; that is why
   `infra/env/*.params` lists every slot rather than patching a few. The check is
   `GET /api/v1/services` returning eight rows.
   **`neo09` and `neo10` are deliberately not in it** (2026-07-29): they are the analytical
   modules and observe the journey rather than sit in it. They still build, deploy and serve
   their own UIs — the orchestrator simply never dispatches to them, and they do not appear
   on the Services screen.
7. **The front end's path prefix is baked at build time.** Vite writes asset URLs into
   index.html when the image is built, and an ALB cannot rewrite paths, so `APP_BASE_PATH`
   is a Docker build argument. It must equal the slot's `PathPrefix` in
   `infra/env/*.params`. A mismatch serves a blank page with 404s for `/assets/…`.
8. **Schema isolation.** Each service owns one MySQL schema and reads no other.
9. **The generator starts off.** The orchestrator calls services that call it back; firing
   at boot means firing at containers that aren't listening.
10. **`ui-kit/` is this repo's design system, and each module owns its own copy.** It was once
    vendored into all eleven frontends and held byte-identical by a CI gate; that gate and its
    script were removed on 2026-07-28 along with every other top-level check on a module repo.
    Fix `ui-kit/` for this frontend. A module's copy is that team's to change.
11. **The mock orchestrator is NOT in this repo, and must not be moved into it.** It lives in
    `github.com/Neueda-Learning/neobank-sidecar` and module repos build it as a git build context. A git
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
| `backend/.../saga/StatusVocabulary.java` | the ten modules' status words → the four the saga acts on |
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
cd backend && ./mvnw test                    # 51, H2, no Docker
cd backend && ./mvnw verify -DskipITs=false  # + 3 real-MySQL (Docker required)
```

`SagaFlowTest` is the one that matters: it drives the state machine with the HTTP call
stubbed and asserts what must *not* happen — no advance past a rejection, no dispatch after
a timeout, no resurrection from a late callback.

`StatusVocabularyTest` carries the vocabulary table, because a journey cannot: it only reaches
the steps it gets to, with whatever outcome the seeded applicant happens to produce, so driving
the saga proves one row at a time by luck.
