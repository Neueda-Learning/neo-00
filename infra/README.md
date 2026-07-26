# Running this stack in AWS

Every repo deploys **its own service** independently. A shared **platform** stack provides the
network, database, cluster, ALB and IAM; each repo's pipeline deploys a **service** stack that
plugs into it. There are two environments, `dev` and `prod`, built from the same templates.

```
platform.yaml  ─▶ neobank-<env>-platform   VPC · ALB+Listener · RDS · cluster · Cloud Map ·
  (laptop, once)                            shared IAM · OIDC · db-init · Exports + SSM
roles.yaml     ─▶ neobank-<env>-roles       one OIDC deploy role per repo + a power role
  (laptop, once)
service.yaml   ─▶ neobank-<env>-<name>      each repo's pipeline deploys its own service;
  (each repo's pipeline)                    imports the platform wiring via Fn::ImportValue
```

| File | What it is |
|---|---|
| `platform.yaml` | The shared environment. Publishes CloudFormation **Exports** (stable wiring) + **SSM** params (the RDS endpoint and ALB DNS — the two values that change on replacement, so they carry no import lock). Owns the account-global GitHub **OIDC provider** on `dev`; `prod` references it. |
| `roles.yaml` | One deploy role per repo (trusts only that repo via OIDC, scoped to its own `neobank-<env>-<name>` CloudFormation stack) + a cluster-scoped **power** role for parking. |
| `service.yaml` | One service: a Fargate task (backend + nginx), a target group, an ALB path rule, a Cloud Map name. Copied identically into every repo; only `env/*.params` differ. |
| `deploy-service.sh` | Deploys this repo's service, pinned to `@sha256` image digests. First deploy parks at 0 → creates its schema → scales to 1. Portable (macOS bash 3.2 safe). |
| `db-init-schema.sh` | Creates **this** service's own schema + grant (as the RDS master). |
| `db-init-appuser.sh`, `sql/appuser.sql` | Platform-only: creates the shared `appuser` once per env. |
| `env/dev.params`, `env/prod.params` | This repo's per-service config (ServiceName, PathPrefix, listener priority, schema, role). The pipeline adds EnvironmentName + the pinned images + DbEndpoint + AlbDnsName. |
| `env/platform-dev.params`, `env/platform-prod.params` | Platform-stack config (ops-only). |

---

## One-time bootstrap (per environment, from a laptop with admin)

The deploy roles GitHub Actions uses are *created by* `roles.yaml`, so the first platform + roles
deploy is done by hand. `dev` first (it owns the OIDC provider), then `prod`.

```bash
export AWS_REGION=ap-southeast-1
ENV=dev            # then repeat with ENV=prod

# 1. platform (references the hand-created ghcr secret in platform-<env>.params)
readparams() { grep -Ev '^[[:space:]]*(#|$)' "infra/env/$1"; }
aws cloudformation deploy --template-file infra/platform.yaml \
  --stack-name neobank-$ENV-platform --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides $(readparams platform-$ENV.params)

# 2. the shared appuser (once)
bash infra/db-init-appuser.sh $ENV

# 3. the per-repo deploy roles (OidcProviderArn is the same for the account)
aws cloudformation deploy --template-file infra/roles.yaml \
  --stack-name neobank-$ENV-roles --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides EnvironmentName=$ENV \
    OidcProviderArn=$(aws cloudformation describe-stacks --stack-name neobank-$ENV-platform \
      --query "Stacks[0].Outputs[?OutputKey=='OidcProviderArn'].OutputValue" --output text)
```

`dev`'s `platform-dev.params` has `CreateOidcProvider=Yes`; `prod`'s has `No` +
`ExistingOidcProviderArn=<the dev-owned ARN>` (deterministic per account, already filled in).

The ghcr pull secret and the `neobank-cfn-<account>-<region>` packaging bucket are **not** part of
any stack — create the secret once (below) and the bucket is made on first deploy; both survive a
teardown.

```bash
aws secretsmanager create-secret --name neobank/ghcr \
  --secret-string '{"username":"gjavolce","password":"ghp_..."}' --query ARN --output text
# put the ARN in GhcrSecretArn= in both platform-*.params
```

---

## GitHub configuration (per repo)

Each repo needs `dev` and `prod` **environments**. Set these **environment variables** (from the
`neobank-<env>-roles` outputs):

| Variable | Value |
|---|---|
| `AWS_REGION` | `ap-southeast-1` |
| `AWS_DEPLOY_ROLE_ARN` | that repo's `neobank-<env>-deploy-<name>` role |
| `AWS_POWER_ROLE_ARN` | `neobank-<env>-power` (only the ops repo, for the Power workflow) |

And these **repository variables** (read by a job `if:`, which can't see environment vars):

| Variable | Set to `true` once |
|---|---|
| `DEV_DEPLOY_ENABLED` | the env's platform + roles exist |
| `PROD_DEPLOY_ENABLED` | ditto for prod |

No cross-repo tokens: each repo resolves only its own image digests, so the old `GHCR_READ_TOKEN`
and `DEPLOY_DISPATCH_TOKEN` are gone.

---

## Routine operation

**Trunk-based, so branches never fight over the one dev stack.** A push to a **feature branch**
runs **build + test only** (verify) — nothing is published or deployed. A push to **`main`** (i.e. a
branch merged back) runs the full flow: build + test → publish images (pinned `@sha256`) → deploy
this repo's service to dev → smoke → record the promote pin in SSM. So dev only moves when
something lands on `main`. (Optional but recommended: protect `main` with *required status checks*
so a branch must pass verify before it can merge.)

**Promoting to prod is a button, gated by an approval.** *Run workflow → `promote: true`* on
`main`. The `deploy·prod` job then **pauses for a required reviewer** to approve in the Actions UI;
on approval it **rebuilds nothing** — it reads the SSM pin (`/neobank/promote/<repo>/*`) and deploys
`neobank-prod-<name>` with the exact digest dev proved.

**The prod gate, in layers:**
1. it only runs on a manual `workflow_dispatch` with `promote: true` (never a push);
2. **only `main` can reach prod** — the promote pin is written only by the main-gated `pin-main`
   job, `deploy-prod` has an `if: github.ref == 'refs/heads/main'` guard, and the `prod`
   environment's **deployment-branch policy** allows only `main`;
3. a **required reviewer** on the `prod` environment must approve before the deploy proceeds
   (GitHub-native; available because the repos are public). Configure under
   *Settings → Environments → prod → Required reviewers*. For real separation of duty (the person
   who triggers can't approve their own run), enable **Prevent self-review** there — leave it off
   only while a single owner both triggers and approves.

**Parking** (compute bill → $0; ALB + RDS keep billing): `./infra/services.sh stop` / `start` /
`status` from a laptop, or the **Power** workflow. Both enumerate services off the cluster, so they
cover any number of teams.

**Adding a team (module `bNN`):** clone the template repo; set its `ServiceName`, `PathPrefix`,
`ListenerRulePriority` (reserved: b01=10, b02=20 … b10=100; orchestrator=50000), `DbName`,
`ServiceId`, `DisplayName` in `env/*.params`; add its `{repo, repoId}` block to `roles.yaml` and
redeploy `neobank-<env>-roles`; set its GitHub environments + vars. It then self-deploys. To wire it
into the orchestrator's saga, add its `Step*` to the orchestrator repo's params and redeploy that.

---

## Connection budget

**One database, 60 connections, eleven services. This is a shared budget, and it has to be
spent deliberately — the failure mode does not look like a shortage.**

The number is measured, not estimated:

```
SHOW VARIABLES LIKE 'max_connections';   -> 60
```

It derives from instance memory (`DBInstanceClassMemory/12582880`), so the only way to raise
it is a bigger instance — and `db.t4g.small` is **refused on this account's free plan**
(tried 2026-07-27; the stack rolled back cleanly). Treat 60 as fixed.

| | pool | × | = |
|---|---|---|---|
| each module | 3 | 10 | 30 |
| orchestrator | 5 | 1 | 5 |
| **committed** | | | **35** |
| spare | | | **25** |

The spare is not slack, it is what the rest of the system runs on: the `db-init` task on every
deploy, the `Database repair` workflow, and anyone with Workbench open.

**Why it matters more than it sounds.** Exhausting the pool does not queue — a starting service
dies in **Liquibase** with `Too many connections`, which reads as a broken module rather than an
exhausted budget. And once the ceiling is hit, `db-init` cannot connect to *create a schema*
either, so the failure spreads from running services into deploys. That is exactly how it broke
on 2026-07-26, with three modules stuck in a state no re-deploy could fix.

**Before raising `DB_POOL_SIZE`** on any service, redo the arithmetic above and check it still
clears 60 with room for db-init. A team that quietly raises its own pool is spending everyone
else's headroom. Check the live number any time with:

```
SHOW STATUS LIKE 'Threads_connected';      -- right now
SHOW STATUS LIKE 'Max_used_connections';   -- high-water mark since restart
```

If `Max_used_connections` is near 60, something is over budget.

---

## Database bootstrap (why it's split)

RDS has no `MYSQL_USER`, and its master may not `GRANT ... ON *.*`. So:
- the **platform** creates the shared `appuser` once (`sql/appuser.sql`, password injected from
  Secrets Manager inside the db-init container — never in a log or the repo);
- **each service** creates and grants **its own** schema on first deploy (`db-init-schema.sh`), run
  as the RDS master. `db/init/00-databases.sql` + `sql/rds-users.sql` are now local-compose/docs only.

### Repairing a schema

`infra/db-reset.sh <env> <unlock|reset>` — **but teams run it from the Actions tab**, not a laptop:
they have no AWS credentials, so the *Database repair* workflow (`db-reset.yml`) is the only path
they have. It runs this script under the repo's own OIDC deploy role. **dev runs immediately; prod
lands on the `prod` environment and therefore pauses for your approval** — no extra gate was built,
it reuses the required reviewer that already guards promotes.

- `unlock` — a task killed mid-migration left `DATABASECHANGELOGLOCK` held and the app now hangs at
  startup on *"waiting for changelog lock"*. Clears the row. No data loss (verified: the request
  rows survived).
  **A stale lock is invisible until a changeset is pending.** Liquibase 4.27's fast check logs
  *"Database is up to date, no changesets to execute"* and returns **before** asking for the lock,
  so a `LOCKED=1` row does not stop a service whose schema is current — proven twice on dev, where
  a deliberately held lock let the app start normally. It detonates on the next push that adds a
  changeset. So clear a stale lock when you see one even if nothing is broken yet.
- `reset` — the schema is unrecoverable (an applied changeset was edited → checksum mismatch, or a
  changeset failed halfway → objects with no changelog row). Drops, recreates, re-grants;
  Liquibase rebuilds from the changelog. **Destroys the schema.** The workflow requires the team to
  type their `DbName` — a typo guard, not authorization.

Both actions **park the service (desiredCount 0) before touching the database and unpark after**,
which is load-bearing twice over: a task hung in Liquibase's retry loop would re-acquire the lock
seconds after the `UPDATE`, and Liquibase only runs at **startup**, so scaling back up is what
rebuilds the schema. Note that re-running `deploy-service.sh` would *not* do it — identical
parameters give CloudFormation an empty changeset and no task rolls.

The schema name is read from the repo's own `env/<env>.params`, never from an argument, so the
supported path cannot be aimed at another team's schema. (The underlying capability is broader —
see the security note at the end of this file.)

---

## Looking inside · troubleshooting

```bash
aws logs tail /neobank/dev --follow
aws ecs execute-command --cluster neobank-dev --task <id> --container backend --interactive --command /bin/sh
```

| Symptom | Cause | Fix |
|---|---|---|
| Task loops RUNNING→STOPPED, deploy rolls back | health check path ≠ nginx prefix | `HealthCheckPath` is `${PathPrefix}/health`; check `PathPrefix` == the image's build-time `APP_BASE_PATH` |
| `Access denied for user 'appuser'` | appuser not created for this env | re-run `db-init-appuser.sh <env>` (idempotent) |
| `Unknown database '<schema>'` | schema not created | re-run `db-init-schema.sh <env>` from that repo |
| App never starts; log repeats "waiting for changelog lock" | a task died mid-migration and left `DATABASECHANGELOGLOCK` held — only blocks when a changeset is pending | *Database repair* workflow → `unlock` (no data loss) |
| Crash-loop on a Liquibase checksum mismatch, or a changeset that failed halfway | an applied changeset was edited, or non-transactional DDL left objects behind | *Database repair* workflow → `reset` (destroys that schema) |
| `Schema-validation: missing table/column …` | entity ≠ changelog | **not a database problem** — add a changeset. Resetting only hides it until the next start |
| Second service's deploy fails on the listener rule | duplicate `ListenerRulePriority` | give it a unique reserved priority |
| A UI loads blank, 404s for `/assets/…` | image built with a different `APP_BASE_PATH` than `PathPrefix` | rebuild / align the prefix |
| Promote does nothing | not on `main`, or `PROD_DEPLOY_ENABLED` ≠ `true` | dispatch from `main`; enable prod |

---

## Teardown

Delete in reverse: every `neobank-<env>-<service>` stack, then `-roles`, then `-platform`. RDS is
`DeletionPolicy: Delete` (disposable pilot). A Secrets Manager secret keeps its name reserved for a
recovery window — `aws secretsmanager delete-secret --secret-id neobank/<env>/appuser
--force-delete-without-recovery` before rebuilding the same env. The ghcr secret and the `-cfn-`
bucket are not in any stack and survive.

---

## Cost

Two environments, two services each, `ap-southeast-1`, roughly **$5/day** (Fargate ~$2.60, 2×ALB
~$1.10, 2×RDS `t4g.micro` ~$0.90, misc ~$0.20). There is deliberately **no NAT gateway** (tasks sit
in public subnets with public IPs behind an ALB-only security group) — it would cost more than
everything else combined. Park with `services.sh stop` when idle; delete the stacks when done.

---

## Security note: the db-init task is a shared master-credential door

The `DbInitTaskDefinition` carries the **RDS master** credentials and takes its SQL as a container
override, and every per-repo deploy role holds `ecs:*` plus `PassRole` on the task execution role.
So **any team's pipeline can already run arbitrary SQL as master — including dropping or unlocking
another team's schema.** `db-reset.sh` does not create that; reading `DbName` from the repo's own
params keeps the *supported* path confined, but the capability underneath is account-wide.

Accepted for a disposable single-account pilot where every repo owner is a student on the same
course, and named here rather than left to be discovered. If it ever needs closing: give each repo
its **own** db-init task definition (scoped to its schema's credentials), or scope `ecs:RunTask` in
`roles.yaml` by task-definition family.
