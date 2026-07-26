# neo-00 — the orchestrator

**The platform for the neo-bank onboarding capstone.** Eleven separately-owned GitHub
repositories — this one and ten student modules — assembled on one laptop with
`docker compose up`, and deployed to AWS by eleven independent pipelines.

This repo owns the **journey**: it creates applications, dispatches each one through the ten
modules in order, collects their answers and decides the outcome. Teams never wire the
journey together and never call each other. Only the orchestrator calls a module.

`neo-01` … `neo-10` are **git submodules** of this repo — one per team, each its own
repository with its own history, CI and AWS service.

```
                              ┌──────────────────┐
                              │      neo-00      │  ← you are here
                              │   orchestrator   │
                              └──┬────────────▲──┘
      POST /api/v1/applications  │            │  PUT /api/v1/applications/{id}
                                 ▼            │
   ┌─────┬─────┬─────┬─────┬─────┴───┬─────┬──┴──┬─────┬─────┐
   │ 01  │ 02  │ 03  │ 04  │ 05      │ 06  │ 07  │ 08  │ 09  │ 10
   │veri-│poli-│ kyc │scre-│ credit  │agree│acc- │card │supp-│analy-
   │ficat│ cy  │     │ening│         │ment │ount │     │ort  │tics
   └─────┴─────┴─────┴─────┴─────────┴─────┴─────┴─────┴─────┴─────┘
```

| # | Module | Domain | Repo |
|---|--------|--------|------|
| 1 | Application Verification | `verification` | [neo-01](https://github.com/gjavolce/neo-01) |
| 2 | Customer Policy | `policy` | [neo-02](https://github.com/gjavolce/neo-02) |
| 3 | Identity Verification (KYC) | `kyc` | [neo-03](https://github.com/gjavolce/neo-03) |
| 4 | Fraud & AML Screening | `screening` | [neo-04](https://github.com/gjavolce/neo-04) |
| 5 | Credit Decisioning | `credit` | [neo-05](https://github.com/gjavolce/neo-05) |
| 6 | Agreement Management | `agreement` | [neo-06](https://github.com/gjavolce/neo-06) |
| 7 | Card Account Setup | `account` | [neo-07](https://github.com/gjavolce/neo-07) |
| 8 | Card Issuing | `card` | [neo-08](https://github.com/gjavolce/neo-08) |
| 9 | Customer Support | `support` | [neo-09](https://github.com/gjavolce/neo-09) |
| 10 | Portfolio & Regulatory Analytics | `analytics` | [neo-10](https://github.com/gjavolce/neo-10) |

## Run it

```bash
git clone --recurse-submodules https://github.com/gjavolce/neo-00.git
cd neo-00
docker compose up --build
```

Then open **http://localhost:3000** and press **+ one**.

> Cloned without `--recurse-submodules`? The `neo-NN/` folders will be empty and the build
> fails on a missing Dockerfile. Fix it with `git submodule update --init`.

That is 23 containers and eleven JVMs. **You do not need all of them** — name the ones you
want and the rest stay down:

```bash
docker compose up orchestrator frontend-00 neo-01 frontend-01
```

A step whose module is not running times out after `CALLBACK_TIMEOUT` and that application
ends `FAILED`. That is the orchestrator behaving correctly, not a broken stack.

| | |
|---|---|
| Orchestrator UI | http://localhost:3000 |
| Orchestrator API | http://localhost:9000 — `/health` · `/info` · `/api/v1/applications` |
| Module UIs | http://localhost:3001 … **3010** |
| Module APIs | http://localhost:9001 … **9010** |
| MySQL | localhost:3326 (`appuser` / `apppass`), eleven schemas `neo_00` … `neo_10` |

To run this alongside another stack, move the orchestrator's ports:

```bash
UI_PORT=3100 API_PORT=9100 MYSQL_PORT=3426 docker compose up --build
```

## Running in AWS

### The two environments

| | Board | Every module |
|---|---|---|
| **dev** | **http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/** | `…/neo-01/` … `…/neo-10/` |
| **prod** | **http://neobank-prod-294820685.ap-southeast-1.elb.amazonaws.com/** | same paths |

The orchestrator owns `/`, so the board is the bare host. Each module hangs off its own
prefix — swap `neo-01` for your number, and append `/health` or `/info` to hit its API:

```
http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-04/         Team 04's UI
http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-04/health   is it up?
http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-04/info     who it says it is
```

<details>
<summary>All ten module UIs on dev</summary>

[01 Application Verification](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-01/) ·
[02 Customer Policy](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-02/) ·
[03 Identity Verification (KYC)](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-03/) ·
[04 Fraud & AML Screening](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-04/) ·
[05 Credit Decisioning](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-05/) ·
[06 Agreement Management](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-06/) ·
[07 Card Account Setup](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-07/) ·
[08 Card Issuing](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-08/) ·
[09 Customer Support](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-09/) ·
[10 Portfolio & Regulatory Analytics](http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-10/)

</details>

**Both are plain HTTP.** There is no certificate and no DNS name — the hostnames above are
the ALBs' own, and they change if an ALB is ever replaced. The current values always come
from SSM, which is also where the pipeline reads them:

```bash
aws ssm get-parameter --name /neobank/dev/alb-dns  --query Parameter.Value --output text
aws ssm get-parameter --name /neobank/prod/alb-dns --query Parameter.Value --output text
```

> **dev moves on every merge to `main`. prod does not.** prod only ever runs an image dev has
> already proven, shipped by a manual `promote` that a human approves — so a 404 there simply
> means that module has not been promoted yet, not that it is broken. If both environments
> are parked (`Power` workflow), both hosts answer 503 until they are started again.

**Every repo deploys itself.** There is no central deploy job: each of the eleven pushes to
its own `main`, publishes its own images to ghcr.io, and converges its own CloudFormation
service stack (`neobank-<env>-<name>`) using its own OIDC role — which is scoped so it
cannot touch anybody else's. The shared platform (VPC, RDS, cluster, ALB) is deployed once
from a laptop and owned by nobody's pipeline.

Prod is never rebuilt: it runs the exact image digests dev proved, behind a required
reviewer.

**Everything is configuration.** The runbook, the config surface, and the cost table are in
[`infra/README.md`](infra/README.md).

```
a team pushes ──▶ ghcr.io ──▶ its own OIDC role ──▶ its own stack ──▶ dev
                                                            └──▶ (review) ──▶ prod
```

| To do this | Edit this |
|---|---|
| Re-order the journey, or drop a step | `infra/env/<env>.params` — the `Step1…Step10` blocks |
| Add a team | one line in `infra/roles.yaml`'s `Repos` map + its GitHub environments |
| Park an environment (costs nothing on Fargate) | run the `Power` workflow with count `0` |

## The journey

`backend/src/main/resources/application.yml` declares the sequence — ten steps, with each
module's `serviceId`, display name and base URL. That file is the **one** place the journey
is defined. Everything else only overrides the URLs:

* `docker-compose.yml` points them at compose service names (`http://neo-04:8080`);
* the ECS task definition points them at Cloud Map (`http://neo-04.neobank-dev.local:8080`),
  built from `infra/env/<env>.params`.

Spring Boot does **not** merge collections across property sources: the highest-precedence
source that supplies `orchestrator.services` supplies all of it. So an environment that
wants nine steps must supply all nine — which is why the params file lists every slot
explicitly rather than patching the list. `GET /api/v1/services` returning **ten** rows is
the check that it worked.

Steps 9 and 10 are the two *analytical* modules. In the capstone spec they observe the
journey rather than sit inside it; here they are dispatched like any other step, so that all
ten teams exercise the same contract and every module can be proven live. When their briefs
land they come out of the sequence and read the journey instead.

## What you'll see

**Screen 1 · Applications.** Every application with a dot per service: grey not reached,
blue in flight, then green `ACCEPTED`, red `REJECTED`, amber `REFERRED`, dark red
`TIMEOUT`. Click a row for its full append-only log.

**Screen 2 · Services.** One box per module: how many applications it is holding right now
(dispatched, no answer yet) and how it has answered so far.

The toggle in the header starts and stops the orchestrator creating applications; **+ one**
makes a single one. It starts **stopped** — the orchestrator calls modules that call it
back, so it must not fire before the stack is up.

## How it works

```
dispatch step N → 202 ack → module decides off-thread → PUT status → step N+1 → … → outcome
```

- The orchestrator **waits for the status update**, not the `202`. The `202` only means
  *received* — it is an acknowledgement, not an answer.
- **Only `ACCEPTED` advances.** `REJECTED` and `REFERRED` end the journey where they happen,
  so a rejection at step 2 means steps 3–10 are never called.
- No answer within `CALLBACK_TIMEOUT` (30s) → the step is logged `TIMEOUT` and the
  application ends `FAILED`. An answer arriving after that is recorded but cannot restart
  the journey.
- Every dispatch, ack, status update, timeout and transition is appended to
  `application_event` and **never updated or deleted**. Both screens are derived from that
  log, which is why they always agree.

Full detail: [`api-contract.md`](api-contract.md).

Out of the box every application reaches `COMPLETED`, because each module's
`ApplicationService` placeholder accepts everything. That is deliberate: it means a journey
that does *not* complete is a genuine fault in the wire, not a module having an opinion. As
teams write real rules, `REJECTED` and `REFERRED` rows start appearing — and unlike a random
outcome, each one can be explained.

## Layout

```
neo-00/
├── docker-compose.yml      the whole stack, 23 containers
├── api-contract.md         what crosses the wire
├── backend/                the orchestrator (Spring Boot 3.3.4, Java 21)
├── frontend/               the two screens (React + Vite, nginx)
├── db/init/                the eleven-schema list (laptop only; AWS is per-deploy)
├── infra/                  the AWS environment, as CloudFormation
├── ui-kit/                 canonical design system, VENDORED into each frontend
├── scripts/
│   ├── sync-design-system.sh   push ui-kit into all 11 frontends · --check for CI
│   ├── make-modules.sh         stamp neo-02…neo-10 from neo-01 · --check for CI
│   └── stamp.py                the ordered literal replacer it uses
└── neo-01/ … neo-10/       submodules — one per team
```

Each repo owns its own MySQL schema (`neo_00` … `neo_10`) and never reads another's — they
integrate over REST, not through shared tables.

Two vendored artefacts, two drift gates, same reasoning: a copy nobody checks is a copy that
diverges.

* `ui-kit/` is vendored into all eleven frontends by `scripts/sync-design-system.sh`. It
  cannot be an npm package: module repos are separate repos, the Docker build context is
  `frontend/`, and the stack must build offline.
* `neo-02` … `neo-10` are **generated from `neo-01`** by `scripts/make-modules.sh`. The ten
  modules are difficulty-equalised copies of one skeleton, and nine hand-maintained near-copies
  diverge within a day — at which point no two teams have the same starting point, which is
  the one thing the design must guarantee.

**Fix `ui-kit/` and `neo-01`, never a copy.** After the repos are handed to teams,
`make-modules.sh` is `--check` only: a regenerate would overwrite a team's work.

**The mock orchestrator is not in here.** It lives in its own repo,
[`gjavolce/neobank-sidecar`](https://github.com/gjavolce/neobank-sidecar), which each module
repo builds directly as a Docker build context — so there is exactly one copy of it in the
world and no team maintains it. (The submodules below are HTTPS, so a git build context
*could* now reach them; the sidecar stays separate for the reason it left in the first place,
which is that scaffolding does not belong inside a graded deliverable.)

## Develop

```bash
cd backend
./mvnw test                       # 31 tests, H2, no Docker
./mvnw verify -DskipITs=false     # + 3 real-MySQL tests (Docker required)

cd ../frontend
npm ci && npm run dev             # http://localhost:5173, proxies to :9000
```

The front end can also be built for a path prefix, which is what a deployed module's image is:

```bash
APP_BASE_PATH=/neo-04 npm run build
```

## Updating the modules

Submodule pointers are pinned commits, so this repo does not follow a team's `main` until
told to. To take everyone's latest:

```bash
git submodule update --remote --merge
git add neo-01 neo-02 neo-03 neo-04 neo-05 neo-06 neo-07 neo-08 neo-09 neo-10
git commit -m "Bump all ten modules"
```

Or one team: `git submodule update --remote --merge neo-04 && git add neo-04`.

## CI

| Job | What it proves | Runs |
|---|---|---|
| `backend` | the orchestrator builds; 31 unit + 3 real-MySQL tests | every push, any branch |
| `frontend` | the UI builds, the design system has not drifted in any of the eleven frontends, and the nine cloned modules still match `neo-01` | every push, any branch |
| `publish` | both images are on ghcr.io, pinned to an immutable `@sha256` digest | `main` only |
| `deploy-dev` | dev converged onto that digest | `main` only |
| `smoke` | dev answers `/health` and serves the UI **through the ALB** | after `deploy-dev` |
| `pin-main` | the digest dev proved is recorded in SSM as the promote source | after `deploy-dev` |
| `deploy-prod` | prod runs that exact digest — no rebuild | manual `promote`, required reviewer |

So a feature branch **verifies only**; nothing is published or deployed until it lands on
`main`. That is what stops many branches during the hackathon fighting over one dev stack.

The `frontend` job checks out submodules recursively, and needs no token: the module repos
are public. It has to — both drift checks *skip* a module whose folder is absent, so without
the submodules the gates would pass while checking nothing.

The sidecar has its own CI in its own repo; nothing here builds or publishes it.

The AWS side — roles, environments, variables, cost — is in
[`infra/README.md`](infra/README.md).
