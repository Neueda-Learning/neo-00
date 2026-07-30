# Local port map

Two different things can run on this laptop, and both can run at once:

1. **The system stack** — `docker compose up` in this folder. One MySQL, the orchestrator, all
   ten modules and eleven frontends. This is the whole product.
2. **A module's standalone stack** — `docker compose up` inside `neo-NN/`. That module's own
   MySQL, its backend, its frontend and a **sidecar** (the mock orchestrator) so the team can
   exercise both halves of the contract without the rest of the system.

They collide out of the box, because every module's `docker-compose.yml` ships the *same*
defaults (`3307 / 8080 / 9000 / 5173`) and the system stack already holds `9000`. Each
`neo-NN/.env` therefore pins a per-module allocation. Those files are **gitignored** — local
machine wiring, not team configuration.

## System stack (`docker compose up` here)

| What | Host port |
|---|---|
| Orchestrator UI | **3000** |
| Module UIs, neo-01 … neo-10 | **3001 … 3010** |
| Orchestrator API | **9000** |
| Module APIs, neo-01 … neo-10 | **9001 … 9010** |
| MySQL — all eleven schemas `neo_00`…`neo_10` | **3326** |
| neo-03's mock identity agency | **8103** |

`API_PORT`, `UI_PORT` and `MYSQL_PORT` override the orchestrator's three if something else
grabs them.

## Standalone module stacks (`docker compose up` in `neo-NN/`)

Keyed to the module number `NN`, so nothing collides and every port is guessable:

```
DB_PORT       = 3400 + NN
BACKEND_PORT  = 8200 + NN
SIDECAR_PORT  = 9100 + NN
FRONTEND_PORT = 5170 + NN
MOCK_PORT     = 8300 + NN     (neo-03 only — the mock identity agency)
```

| Module | Domain | MySQL | Backend | Sidecar | Frontend | Mock |
|---|---|---|---|---|---|---|
| neo-01 | verification | 3401 | 8201 | 9101 | 5171 | — |
| neo-02 | policy | 3402 | 8202 | 9102 | 5172 | — |
| neo-03 | kyc | 3403 | 8203 | 9103 | 5173 | 8303 |
| neo-04 | screening | 3404 | 8204 | 9104 | 5174 | — |
| neo-05 | credit | 3405 | 8205 | 9105 | 5175 | — |
| neo-06 | agreement | 3406 | 8206 | 9106 | 5176 | — |
| neo-07 | account | 3407 | 8207 | 9107 | 5177 | — |
| neo-08 | card | 3408 | 8208 | 9108 | 5178 | — |
| neo-09 | support | 3409 | 8209 | 9109 | 5179 | — |
| neo-10 | analytics | 3410 | 8210 | 9110 | 5180 | — |

**Host ports are for you, not for the services.** A module reaches its sidecar as
`http://sidecar:8080` over the compose network, so changing `SIDECAR_PORT` never affects the
callback wire — only what you type in a browser.

## Memory is the real limit, not ports

Docker's VM here has **7.8 GiB**. The system stack alone is ~4.5 GiB across 24 containers;
adding two standalone stacks took it to **~6.3 GiB across 33**. Roughly **1.4 GiB per standalone
stack** (backend + sidecar + MySQL + nginx), so about **two** fit alongside the system stack.

The system stack pins `mem_limit: 512m` + `-XX:MaxRAMPercentage=70` on every service. **The
module stacks pin nothing** — an unpinned JVM sizes its heap off the *host's* RAM and takes a
quarter of it. That is why `neo-03-backend` has been seen `Exited (137)`: OOM-killed, which
reads like a crash in the team's code and is not. If you need more than two standalone stacks
at once, raise Docker Desktop's memory rather than hunting a bug.

## Checking what is actually bound

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | sort
```
