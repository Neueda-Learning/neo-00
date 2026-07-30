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
| neo-01 | verification | 3401 | **8080** | 9101 | 5171 | — |
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

### Why neo-01's backend is 8080 and not 8201

`neo-01/frontend/src/api.js` hardcodes the backend as `<protocol>//<hostname>:8080` whenever the
page is served from `localhost` (its `LOCAL_BACKEND` constant). No other module does this — the
other nine use `VITE_API_BASE || ''` and talk same-origin through nginx. neo-01's UI therefore
only works when its backend really is on 8080, so the scheme bends for the one module that needs
it. Nothing else wants 8080.

**The same line breaks the SYSTEM stack's neo-01 UI.** At `http://localhost:3001` the page loads
but every call goes to `localhost:8080` — where, in the system stack, nothing is listening — and
the board shows *"Could not load cases · Failed to fetch"*. The backend is fine (`:9001` and the
nginx proxy both answer 200); only the browser is misrouted. Two ways out:

- **Now, no changes:** browse it as **`http://0.0.0.0:3001`**. Any hostname that is not literally
  `localhost` or `127.0.0.1` skips the heuristic, so the app falls back to same-origin. Verified.
- **Properly:** team 01 deletes the `LOCAL_BACKEND` constant and uses
  `const BASE = import.meta.env.VITE_API_BASE || ''` like the other nine. It cannot be fixed from
  outside their repo — the correct value for the system stack is the empty string, and
  `VITE_API_BASE` is only consulted when truthy.

It does not affect AWS: there the hostname is the load balancer's, so `LOCAL_BACKEND` is empty
and the app is same-origin. This is a localhost-only fault.

## Memory is the real limit, not ports

Docker's VM here has **7.8 GiB**, and the system stack alone is ~4.5 GiB across 24 containers.

The system stack pins `mem_limit` + `-XX:MaxRAMPercentage=70` on every service. **The module
stacks pin nothing** — an unpinned JVM sizes its heap off the *host's* RAM and takes a quarter of
it. Two unpinned module stacks fit beside the system stack; **the third does not**. Starting it
had the kernel OOM-kill three MySQLs in a row (`neo-09-mysql`, `neo-03-mysql`, then `neo-01-mysql`
when the first two were restarted). `Exited (137)` on a database reads like a crash in someone's
code and is nothing of the sort.

So pin them the same way, with a local overlay that touches no team repo:

```bash
docker compose -f neo-01/docker-compose.yml -f local/module-limits.yml \
               -p neo-01 --env-file neo-01/.env up -d

# neo-03 has a third container, so it stacks one more overlay:
docker compose -f neo-03/docker-compose.yml -f local/module-limits.yml \
               -f local/module-limits-neo-03.yml \
               -p neo-03 --env-file neo-03/.env up -d
```

Measured: **three** standalone stacks + the system stack = **37 containers at 6.34 GiB, all
healthy** — less memory than the 35 containers used unpinned, with two of those already dead. A
fourth stack is still the point to raise Docker Desktop's memory rather than hunt a bug.

## Checking what is actually bound

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | sort
```
