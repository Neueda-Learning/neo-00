# Operations runbook — neobank infra (attempt-02)

**Read this to pick the system back up and run it day to day.** It is the *task-oriented*
companion to [`README.md`](README.md) (which is the *reference* — what each file is, the one-time
bootstrap, GitHub config, and the design rationale). When in doubt about **how something works**,
read README; when you just want to **do a thing**, stay here.

- **Account:** `192404871113` · **Region:** `ap-southeast-1` · **Prod approver:** `gjavolce`
- Everything runs on ECS Fargate behind one ALB per environment, against one RDS MySQL per
  environment. No laptop server, no Kubernetes. You need the AWS CLI + `gh`, and admin creds only
  for the rare platform-level jobs (§6).

---

## 1. At a glance — what is deployed right now

Two environments, four CloudFormation stacks each:

| Stack | What it is | Deployed by |
|---|---|---|
| `neobank-<env>-platform` | VPC · ALB+Listener · RDS · ECS cluster · Cloud Map · shared IAM · OIDC · db-init · Exports+SSM | **you, from a laptop** (rare) |
| `neobank-<env>-roles` | one OIDC deploy role per repo + the power role | **you, from a laptop** (rare) |
| `neobank-<env>-orchestrator` | the orchestrator service (neo-00) | **neo-00's pipeline** |
| `neobank-<env>-neo-01` | the module service (neo-01) | **neo-01's pipeline** |

| | URL |
|---|---|
| **dev** board | http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/ |
| **dev** module | http://neobank-dev-571740187.ap-southeast-1.elb.amazonaws.com/neo-01/ |
| **prod** board | http://neobank-prod-294820685.ap-southeast-1.elb.amazonaws.com/ |
| **prod** module | http://neobank-prod-294820685.ap-southeast-1.elb.amazonaws.com/neo-01/ |

Re-derive any of this at any time (URLs change only if the platform stack is rebuilt):

```bash
export AWS_REGION=ap-southeast-1
# every neobank stack + status
aws cloudformation list-stacks \
  --query "StackSummaries[?starts_with(StackName,'neobank') && StackStatus!='DELETE_COMPLETE'].[StackName,StackStatus]" \
  --output text | sort
# the ALB URL for an env
aws ssm get-parameter --name /neobank/dev/alb-dns --query Parameter.Value --output text
```

---

## 2. Mental model (30 seconds)

- **Platform is shared; services are per-repo.** The platform stack publishes its wiring as
  CloudFormation **Exports** (+ two SSM params for the RDS endpoint and ALB DNS). Each service
  stack imports that wiring, so a service knows nothing about VPCs — only its own image, path and
  schema.
- **Teams never hold AWS credentials.** Everything that touches AWS is a **GitHub Actions
  workflow** in a repo, assuming that repo's own scoped **OIDC role**. That is why "start the
  services", "repair a schema", etc. are all either a laptop script (you, admin) or a workflow.
- **Trunk-based deploys:** a feature-branch push only **verifies**; a push to **`main`** deploys to
  **dev**; a manual, approved **promote** ships the *exact dev digest* to **prod** (no rebuild).
  Full detail in [`README.md` → Routine operation](README.md).

---

## 3. ▶️ Startup / ⏸️ Shutdown / 📊 Status  (the cost lever)

Parking scales every service to **0 tasks**. On Fargate that is **$0 compute** while parked; the
ALB and RDS keep billing (~$2/day/env). Unparking scales back to 1. **The stacks, images, database
and URLs are all untouched** — parking is a power switch, not a deploy, so `start` brings the same
environment back at the same address.

### From a laptop (you have admin) — `infra/services.sh`

```bash
cd neo-00                 # run from the repo root

./infra/services.sh status     # what is running right now (read-only)
./infra/services.sh stop       # park BOTH dev and prod → 0 tasks
./infra/services.sh start      # unpark BOTH → 1 task each
./infra/services.sh stop dev   # just one environment
./infra/services.sh start prod

# start to more than one task per service (rarely needed):
START_COUNT=2 ./infra/services.sh start dev
```

It enumerates services off the cluster, so it always covers **every** team's service, however many
exist. `stop`/`start` wait for the services to settle before returning.

### From GitHub (no laptop AWS creds) — the **Power** workflow

neo-00 → **Actions → Power → Run workflow** → pick `environment` (dev/prod) and `count`
(`0` to park, `1` to run). Same effect as `services.sh`, driven by the cluster-scoped power role
(`AWS_POWER_ROLE_ARN`).

### What each control actually stops

| Action | Compute (Fargate) | ALB | RDS | URLs / data |
|---|---|---|---|---|
| `services.sh stop` / Power `0` | **$0** | still billing | still billing | unchanged |
| `services.sh start` / Power `1` | back on | — | — | unchanged |
| **Teardown** (§7) | $0 | deleted | deleted | **gone** |

So: **park overnight / between sessions** (fast, reversible, keeps data). **Tear down** only when
you are done with an environment for good (stops *all* billing, destroys the data and the URL).

> A `main` deploy **unparks** the service it deploys (it sets DesiredCount back to 1). So if you
> parked and then merge to `main`, that service comes back up on its own.
>
> Want to stop the RDS bill too without a teardown? `aws rds stop-db-instance --db-instance-identifier
> neobank-<env>` — but AWS **auto-starts a stopped RDS after 7 days**, and the app can't run while
> it's stopped, so this is only for a deliberate long pause. Parking the services is the normal move.

---

## 4. Everyday operations

| I want to… | Do this |
|---|---|
| **Deploy a change to dev** | Merge a PR to `main` in that repo. Its pipeline builds → publishes a pinned `@sha256` image → deploys its service to dev → smokes it → records the promote pin. (A feature-branch push only verifies.) |
| **Promote dev → prod** | In the repo: **Actions → Pipeline → Run workflow**, branch **`main`**, tick **promote**. The `deploy·prod` job **pauses** — open the run, **Review deployments → Approve**. Ships the exact digest dev proved; no rebuild. |
| **See what's running** | `./infra/services.sh status` |
| **Tail logs** | `aws logs tail /neobank/dev --follow` (add `--since 10m`, or `\| grep neo-01`) |
| **Shell into a running task** | `aws ecs execute-command --cluster neobank-dev --task <id> --container backend --interactive --command /bin/sh` |
| **Health-check through the ALB** | `curl http://<alb>/health` · `curl http://<alb>/neo-01/health` |
| **Run a journey (smoke)** | `id=$(curl -s -XPOST http://<alb>/api/v1/applications \| jq -r .id); curl -s http://<alb>/api/v1/applications/$id \| jq .overallStatus` |
| **Repair a stuck schema** | *Database repair* workflow in the repo's Actions tab — `unlock` (stale changelog lock) or `reset` (destroys that one schema). dev runs immediately; **prod pauses for approval**. See [`README.md` → Repairing a schema](README.md). |
| **Add an approver / change the prod gate** | Repo → **Settings → Environments → prod → Required reviewers** (and **Prevent self-review** for real separation of duty). |

---

## 5. Who can deploy / approve (the gates)

- **dev** moves only when something lands on **`main`** (branch protection requires the two build
  checks to pass before a PR can merge).
- **prod** needs all of: a manual `promote` run, on `main`, with `PROD_DEPLOY_ENABLED=true`, **and**
  a required reviewer's approval. Four independent layers; a feature branch can never reach prod.
- **No stored AWS keys anywhere** — every deploy assumes a per-repo OIDC role scoped to that repo's
  own `neobank-<env>-<name>` stack.

---

## 6. First-time setup / rebuilding an environment from scratch (admin, laptop)

Only needed once per environment, or after a teardown. **dev first** (it owns the account-global
OIDC provider), then **prod**. Full commands and the GitHub-side config are in
[`README.md` → One-time bootstrap](README.md); the shape is:

```bash
export AWS_REGION=ap-southeast-1
ENV=dev                                   # then repeat with ENV=prod
readparams() { grep -Ev '^[[:space:]]*(#|$)' "infra/env/$1"; }

# 1) platform  2) shared appuser  3) per-repo deploy roles
aws cloudformation deploy --template-file infra/platform.yaml \
  --stack-name neobank-$ENV-platform --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides $(readparams platform-$ENV.params)
bash infra/db-init-appuser.sh $ENV
aws cloudformation deploy --template-file infra/roles.yaml \
  --stack-name neobank-$ENV-roles --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides EnvironmentName=$ENV \
    OidcProviderArn=$(aws cloudformation describe-stacks --stack-name neobank-$ENV-platform \
      --query "Stacks[0].Outputs[?OutputKey=='OidcProviderArn'].OutputValue" --output text)
```

Then set each repo's GitHub environment vars (`AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`,
`AWS_POWER_ROLE_ARN` on the ops repo) and the repo vars (`DEV_DEPLOY_ENABLED`/`PROD_DEPLOY_ENABLED`),
and push `main` in each repo — the pipelines create the service stacks (park → schema → scale).

**Gotchas that already bit us (baked into the scripts, but know them):** the db-init container's
EntryPoint is `[sh,-c]`, so overrides pass the SQL script *alone*; schema SQL goes through a quoted
heredoc, never `mysql -e "…backtick-quoted…"`; the scripts avoid `mapfile` (macOS bash 3.2); a fresh
`t4g.micro` RDS can throw one transient *Communications link failure* on first connect — the ECS
deployment circuit breaker rides it out.

---

## 7. Teardown (stops ALL billing; destroys data + URL)

Delete in reverse dependency order, per environment:

```bash
export AWS_REGION=ap-southeast-1; ENV=dev
aws cloudformation delete-stack --stack-name neobank-$ENV-orchestrator
aws cloudformation delete-stack --stack-name neobank-$ENV-neo-01
# wait for those to finish, then:
aws cloudformation delete-stack --stack-name neobank-$ENV-roles
aws cloudformation delete-stack --stack-name neobank-$ENV-platform
# free the appuser secret name before rebuilding the SAME env (name is held for a recovery window):
aws secretsmanager delete-secret --secret-id neobank/$ENV/appuser --force-delete-without-recovery
```

RDS is `DeletionPolicy: Delete` (disposable pilot). The ghcr pull secret and the
`neobank-cfn-<account>-<region>` packaging bucket are **not** in any stack and survive. Deleting the
**dev** platform also deletes the account-global OIDC provider — so if both envs exist, tear down
prod's services first and leave dev's platform until last, or plan a short window where nothing
deploys.

---

## 8. Where things live

```
neo-00/
├── infra/
│   ├── platform.yaml            shared env (VPC/ALB/RDS/cluster/IAM/OIDC/db-init) → Exports+SSM
│   ├── roles.yaml               per-repo OIDC deploy roles + power role
│   ├── service.yaml             ONE service (copied identically into every repo)
│   ├── deploy-service.sh        a repo deploys its own service (park→schema→scale on first deploy)
│   ├── db-init-appuser.sh       platform: create the shared appuser (once per env)
│   ├── db-init-schema.sh        a service creates its own schema
│   ├── db-reset.sh              self-service schema repair (unlock / reset) — run via the workflow
│   ├── services.sh              ▶️⏸️ start / stop / status  (this doc, §3)
│   ├── env/                     dev.params, prod.params (per-service) · platform-*.params (ops)
│   ├── sql/appuser.sql          the shared-user SQL
│   ├── README.md                the REFERENCE (design, bootstrap, GitHub config)
│   └── OPERATIONS.md            this runbook
└── .github/workflows/
    ├── pipeline.yml             verify (branch) · deploy dev (main) · promote prod (approved)
    ├── power.yml                ▶️⏸️ start/stop from GitHub (no laptop creds)
    └── db-reset.yml             Database repair (unlock / reset), prod pauses for approval
```

`service.yaml`, `deploy-service.sh`, `db-init-schema.sh`, `db-reset.sh`, `pipeline.yml` and
`db-reset.yml` are **byte-identical in every repo** — fix them here, then copy into each module
repo. `neo-01` is the template every future module (`b02`…`b10`) is cloned from.
```
