# Absorb the simulator into neo-00 as its own page

*Plan, 2026-07-30. Not yet built.*

## Context

Three options were weighed for "fire the scenario corpus at a chosen `/neo-xx/` module." This is
the third, and it is strictly better than the other two.

| | where | the callback |
|---|---|---|
| Local sidecar → deployed module | laptop | module reports to neo-00, which discards it. No decision returns, read-back 404s. **Works today, nothing to build.** |
| Sidecar hosted as a 12th ECS service | AWS | same discard, plus a new stack, schema, image, listener rule, IAM slot |
| **Simulator inside neo-00** | AWS, already deployed | **dissolves** — neo-00 owns the id, so the report is its own |

**Why.** A sidecar-issued `applicationId` is unknown to the orchestrator, so
`SagaStore.recordApplicationStatusUpdate` (`SagaStore.java:221-225`) returns `Ignored` *before*
`events.save` — `200`, one WARN, zero durable state. Correct, and exactly the "requests for
non-existing applications are not considered" behaviour that was asked for, but the module's
decision goes into a hole. If the **orchestrator** issues the id, that same `PUT` arrives at
something it knows; it just belongs to a simulation rather than a journey.

Three things only this option gets:

- **Read-back works.** `GET /api/v1/applications/{id}` is contract surface for the ten services
  (`api-contract.md` §5). Serving simulation applications there fixes the
  `404 → NoSuchElementException` that breaks neo-01, 05 and 07 outside a real journey.
- **It cannot drift.** §15's argument against a richer sidecar was "a mock that drifts from what it
  mocks is worse than none." Reusing the orchestrator's own `ApplicationRequest`, its `RestClient`
  bean and its `StatusVocabulary` means it **is** the thing it mocks.
- **The raw status word becomes visible.** `application_event.status` stores the *canonical* word
  (`SagaStore.java:240-249`), and even the fallback is post-`normalize()` — so a module's literal
  `in-progress` or `CLEAR` survives nowhere but a log line. A simulation row stores both.

**Scope.** The standalone `neobank-sidecar` stays untouched and frozen at `v1` — it is the
orchestrator you can run *offline*, before your module is deployed. This page is the orchestrator
itself. Neither replaces the other.

**Two blockers to clear first.**

1. **neo-00 has 43 uncommitted paths / ~685 insertions in flight** — product catalogue, agreement
   e-sign hold, support tickets, `004-add-awaiting-signature`, three new frontend components. It
   touches `SagaStore.java`, `SagaDtos.java`, `ServiceRegistry.java`, `application.yml`,
   `db.changelog-master.yaml`, `infra/service.yaml`, `frontend/src/api.js` and `status.js` —
   every file this plan touches. Land or branch from it. The new changeset is **`005-`**.
2. **The endpoint must live under `/api/`.** `frontend/nginx.conf.template` proxies only
   `^~ ${BASE_PATH}/api/` plus exact `/health` and `/info`; anything else is caught by the SPA
   regex and returns **index.html with HTTP 200** — a silent failure, not a 404. `vite.config.js`
   has the same three keys, so `npm run dev` fails identically. Use `/api/v1/simulator/**` and
   neither file needs touching.

---

## Part A — backend, `com.neobank.orchestrator.simulator`

A sibling of the saga, not a change to it. A simulation never writes an `application` row and never
an `application_event`, so the board, the event log and the service tallies stay clean.

### A1. The corpus

**Source it from `Neueda-Learning/neobank-sidecar@v1`** — `src/main/resources/scenarios/`, 27 files
— into `backend/src/main/resources/scenarios/`. **Not** from `neo-01/reference/scenarios_raw.json`:
that dump lives inside a team-owned submodule *and* its dates are already resolved
(`"dateOfBirth": "2008-07-28"` where the source has `{{today-18y}}`), so the age-boundary scenarios
would start rotting again on day one.

Port `ScenarioLibrary.java` (~200 lines): named-file loading from `index.json` — no classpath
wildcard, because a fat jar resolves those differently and a corpus that silently shrinks when you
containerise is a bad hour — plus the `{{today}}` / `{{today-18y}}` / `{{today-18y+1d}}` token
resolution. **Drop the `SCENARIOS_DIR` overlay** (needs a volume; Fargate makes that awkward and the
instructor can edit the repo).

**Three drifts in the corpus, each with a different answer:**

| drift | do |
|---|---|
| SIM-03 / SIM-04 use `CREDIT_CARD_STUDENT` | **Fix to a catalogue product.** They test the 18th-birthday boundary; an unknown product code makes neo-01 reject them at the wrong rule and the age test never runs. |
| SIM-24 uses `CREDIT_CARD_PREMIUM` | **Leave it.** An unknown product code *is* the scenario. |
| envelopes have 4 keys; `ApplicationRequest` now has 5 (`outputs`) | Pass `outputs = null`. See the limit below. |

**Known limit, state it in the UI:** a one-shot dispatch cannot carry the `outputs` accumulated by
steps 1..N-1, so a module that reads them (the four teams told "outputs exists now") sees an empty
map. The envelope editor is the mitigation — an operator can paste an `outputs` object in. Say so
in a `Caption` on the screen rather than letting a team discover it as a bug.

Port `ScenarioLibraryTest` **and** the CI check that counts scenario files inside the built jar.

**Duplication, stated:** the corpus now exists in two repos — neo-00's copy canonical, the sidecar's
the frozen `v1` snapshot.

### A2. `005-create-simulation.yaml` — one table, both halves

Conventions from the existing four changesets: filename stem = changeSet `id`, `author: neo-00`,
always a `rollback:` block, **no `TEXT`** (H2 and MySQL render it differently and `ddl-auto=validate`
then refuses to boot on one), **no `BOOLEAN`** (Liquibase emits `TINYINT` on MySQL, Hibernate expects
`BIT`, same outcome).

Columns: `id` · `application_id` · `correlation_id` · `scenario_id` · `target_service_id` ·
`target_url` · `sent_at` · `ack_http_status` · `ack_body` · `reported_service_id` ·
**`reported_status`** · **`canonical_status`** (nullable) · `reported_comment` · `reported_at` ·
`application_json`.

- **`reported_status` is the exact string off the wire, before `normalize()`.** That is the whole
  point of A5 and it cannot be recovered from `application_event`.
- **`application_json VARCHAR(4000)`, never 8000.** InnoDB checks *declared* sizes against the
  65535-byte row limit and utf8mb4 costs 4 bytes/char. The largest corpus application is 1097
  chars. Oversized is stored as **nothing**, never truncated — half a JSON document is a parse
  error served as if it were an application.
- **Order by `id DESC`, never `created_at DESC`.** MySQL `TIMESTAMP` is second-precision and a
  "send all" writes 26 rows inside one second; the list would shuffle between refreshes.
- A new **table** also dodges the `application` row-size budget (already 41152 of 65535 after `003`).

### A3. `SimulationService` — modelled on `SupportClient`, not on `SagaEngine`

`SupportClient` is the blessed precedent: an address-configured one-shot that builds its own
envelope and never touches the saga, with the class javadoc stating exactly that rule. Copy its
shape, and reuse `UpstreamModuleException` + `ApiExceptionHandler` so a failed dispatch surfaces as
a message rather than a 500.

**Do not reuse the saga's dispatch path.** `SagaStore.beginDispatch` mutates `current_step`, saves
the row and writes a `REQUEST_SENT` event — and returns empty for any step past 8, so it could never
reach neo09/neo10 anyway. `recordAck` writes `ACK_RECEIVED`, which feeds the board dots and
`serviceSummaries()`. `SagaEngine.dispatch`'s failure branch calls `recordDispatchFailed`, which ends
the journey `FAILED`. **Do reuse** the `restClient` bean from `AppConfig` (3s connect / 5s read,
already tuned for "a 202 should arrive in milliseconds") and the `SagaDtos.ApplicationRequest`
record — that record is the anti-drift argument.

- **Dispatch is synchronous inside the HTTP request**, as the sidecar's is. No new thread pool, no
  new `DataSource`: the connection budget is already 35 of a measured 60 and the orchestrator holds
  5 of it.
- **Unreachable is a result, not an error**: status `0` plus the exception text.
- **Ids are always freshened.** Save the row first, then compose `SIM-01-neo05-<rowId>` from the
  autoincrement — a counter would collide after a restart. Rewrite **both** copies (envelope and
  nested `application.applicationId`); rewriting one and not the other is the classic form of this
  bug. *SIM-25 loses its point under this — its scenario is a deliberate duplicate id. One line in
  the docs, not a fix.*
  **One row per dispatch:** insert → read the id → rewrite → POST → record the ack **on that same
  row**. Not insert, dispatch, insert again.
- **Simulation ids must never appear in the `application` table.** `GeneratorSeed.numberIn` parses
  the digits after the last dash of every id there, so `SIM-26` would read as sequence 26 and
  corrupt the generator's numbering. The separate table gives this for free — and also excludes
  simulations from `TimeoutSweeper`, which only scans `application`.
- **Report**: *matched* means a `simulation` row already exists with that `application_id`. Pair
  oldest-unanswered-first; if every row for that id has answered, log a duplicate and drop.
  `report()` returns a boolean so A4 can tell the two cases apart.
  **An id belonging to neither the saga nor a simulation is NOT stored** — it keeps today's WARN
  and drops. The sidecar records those as `unsolicited` rows because one team owns that box; this
  one is shared, public and unauthenticated, and every stray callback in the estate would land in
  it — team 01's local sidecar reports at the dev ALB *today*. Unbounded growth in the
  orchestrator's own schema is not worth a lost-and-found.
- No state machine, no sequencing, no retry, no sweeper. The saga has all four.

### A4. One branch, and two things currently unpinned

Add a fifth variant to the sealed `CallbackOutcome` rather than string-matching `Ignored.why()`:

```java
/** Not a journey. If a simulation owns this id the simulator takes it; otherwise it is litter. */
record Unknown(String applicationId) implements CallbackOutcome { }
```

`SagaStore` returns it in place of today's `Ignored("unknown application …")`; `SagaEngine` branches
on it and calls `simulations.report(...)`. This keeps `SagaStore` free of any simulator dependency
and makes the compiler show every site — `SagaEngine` only tests `instanceof Advance` today, but
**check for an exhaustive `switch` over `CallbackOutcome` before adding the variant.**

In `ApplicationController`: on a `GET /{id}` miss, fall through to the simulation's stored
application before 404. **`{id}` only — not `?name=`, and not the bare board.** `{id}` is the
endpoint the read-back gap actually needs (neo-01, 05, 07). `?name=` would mislead: the corpus is
one applicant repeated — 26 scenarios are SIM-01 with a single field changed, so nearly every one
is *Maria Nowak*, and `ApplicationFactory`'s cast leads with Maria Nowak too. A search for `nowak`
would return real journeys drowned in simulations, and neo-09 consumes that endpoint as a
`List<Application>`. **Stated limit:** a team testing via the simulator cannot exercise `?name=`.
The bare board is where "not considered" has to hold.

Then close the two gaps the current code leaves:

- `SagaStore.java:206-208` javadoc says *"the event is appended whatever it says"*. False for this
  branch, which returns nine lines earlier. Fix it.
- `SagaFlowTest.callbackForAnUnknownApplicationIsIgnoredNotFatal` asserts only "did not throw".
  Make it assert no `application` row, **no `application_event` row**, and unchanged
  `serviceSummaries()` tallies. The whole design rests on that and nothing holds it.

### A5. Raw word beside canonical

Run the report through `StatusVocabulary` and store both columns. `neo04` sends `CLEAR`; the board
paints `ACCEPTED`. The page shows `CLEAR → ACCEPTED`, or — when `canonical()` returns empty — the
warning `SagaStore.java:278-283` already composes, using the existing public
`StatusVocabulary.acceptedWords(serviceId)`: *"unknown word; `neo04` may send: … (case-insensitive;
`-` and `_` are the same)."* All three helpers are `public static` and need no change.

### A6. Targets — all ten

`ServiceRegistry` holds the **eight** journey steps and `registry.size()` *is* the journey length;
`OrchestratorApplicationTests` actively asserts neo09/neo10 are absent from `GET /api/v1/services`.
So do not feed the picker from `api.services()` and do not extend `services[]`. Add a sibling block
in the established idiom (`orchestrator.support`, `orchestrator.catalogue` are the precedents):

```yaml
orchestrator:
  simulator:
    enabled: ${SIMULATOR_ENABLED:true}
    targets:
      - { serviceId: neo01, name: "Application Verification", base-url: "${SERVICE_01_URL:http://localhost:9001}" }
      …
      - { serviceId: neo09, name: "Customer Support",                 base-url: "${SERVICE_09_URL:http://localhost:9009}", analytical: true }
      - { serviceId: neo10, name: "Portfolio & Regulatory Analytics", base-url: "${SERVICE_10_URL:http://localhost:9010}", analytical: true }
```

**All ten, with 09/10 flagged `analytical`** and labelled as such in the picker. Dispatching an
application at them proves nothing about their brief, but "is my container up and does it ack" is
worth one click, and omitting two teams from a shared tool reads as a mistake.

**No per-target path.** Every target gets the contract path `/api/v1/applications`. neo-09 also
serves `/api/v1/support/execute`, but that takes a different envelope shape entirely — a
configurable path would invite someone to point the corpus at it and get a confusing 4xx.

Wiring for the one address that does not exist yet:

- `docker-compose.yml` — add `SERVICE_10_URL: http://neo-10:8080` and amend the comment that says
  *"No `SERVICE_10_URL`: nothing calls neo-10 yet."* Something does now.
- `infra/service.yaml` — add `AnalyticsBaseUrl` beside the existing `SupportBaseUrl`; `Step9`/`Step10`
  are **not** reintroduced.
- `infra/env/{dev,prod}.params` — `AnalyticsBaseUrl=http://neo-10.neobank-dev.local:8080`.

**No free-text target URL.** The ALB is `0.0.0.0/0` with no auth; targets come from configuration
only, so this cannot become a fetch-arbitrary-URL proxy into the VPC. That is why the sidecar's
`moduleUrl` field is deliberately not ported.

### A7. `SimulatorController` — `/api/v1/simulator/**`

Mirror the sidecar's vocabulary so the mental model transfers: `GET /scenarios` · `GET /targets` ·
`POST /dispatch` `{scenarioId | envelope, targetServiceId}` · `GET /dispatches?target=` ·
`DELETE /dispatches?target=` (**scoped** — an unscoped clear on a shared box means one team wipes the
other nine). Gated on `orchestrator.simulator.enabled`.

Add one row to `api-contract.md` §5 marking the group **instructor tooling, not module contract** —
a module must never call it.

### A8. Tests

Ported `ScenarioLibraryTest` (every shipped scenario parses; SIM-03 is exactly 18 and SIM-04 is 17
whatever day it runs) · fresh id moves both copies and leaves SIM-26's absent id alone · unreachable
target records ack `0` · report pairs oldest-unanswered-first, and an id owned by neither the saga
nor a simulation is **dropped, writing no row anywhere** (A3) · **the A4 branch**: a report for a
simulation creates **no** `application_event`, while a report for a live journey still advances it ·
`GET /{id}` falls through to a simulation but **neither the bare board nor `?name=` does** ·
`StatusVocabularyTest` extended for the raw/canonical pair · a real-MySQL `*IT`
(`OrchestratorSchemaIT` is the existing guard) for the `VARCHAR(4000)` row-size rule and the
`id DESC` tiebreak — H2 catches neither.

---

## Part B — frontend

**A fourth top-level view, not a backoffice tab.** Three reasons, in order of weight:
`App.jsx` states *"each screen renders its own AppShell, because the bar differs"* — as a tab the
simulator inherits the generator interval box, `+ one` and the `Stepping` toggle, none of which
belong to it; as a tab it also inherits `BackofficeScreen`'s 1s five-endpoint `reload()` and 10s
identity poll, whereas `App.jsx`'s `{view === … && …}` unmounts inactive screens so only the active
one polls; and `BackofficeScreen`'s content render is a binary ternary that a third tab would have
to rewrite, while the `App.jsx` chain is genuinely append-only.

Archetype: **Panel** — DESIGN.md §5, *"make a dependency misbehave … a `Timeline` of recent calls.
This is the demo that lands for an integration module."* The pickers are its controls, the log is
its timeline. Borrow **Config**'s prefilled-form-plus-append-only-history idea for the envelope
editor.

| File | Change |
|---|---|
| `src/App.jsx` | one import + one `{view === 'simulator' && <SimulatorScreen onHome={goHome} />}` |
| `src/components/LandingScreen.jsx` | a third `<button className="choice">`. `styles.css` uses `repeat(auto-fit, minmax(300px, 1fr))` — **no CSS change** |
| `src/components/SimulatorScreen.jsx` | **new**, the only real work |
| `src/api.js` | `scenarios` · `targets` · `dispatch` · `dispatches` · `clearDispatches`, with the house-style comment saying why it goes through the orchestrator |
| `src/status.js` | one `httpTone` mapper — `status.js` owns every status→tone translation, screens never inline one |

Composition, following DESIGN.md §6/§7 literally: own `AppShell` + `TopNav`
(`brand="NEO" product="Simulator"`, health `StatusPill`, `← Home`) · one `PageHeader` · a `Toolbar`
of two `Field`+`Select` pickers, the scenario list **sorted by the chosen target's domain** using the
index's `modules` array (that affordance is free) · a `Split` with the envelope `Textarea mono` on
one side and the response `CodeBlock` on the other · `FormActions` with the **one** primary button,
**Send** · a `DataTable` log with `renderExpanded`. `SIM-01`, `neo04` and reason codes are `Tag`s;
HTTP `202` is a `Badge` (a decision); "module unreachable" is an `Alert`; the `EmptyState` says
*"Pick a scenario and press Send"*.

Two details worth taking: `DataTable`'s `rowTone` prop exists and is **unused anywhere in neo-00** —
free colour-coding for 202 vs 4xx; and `clock()` in `status.js` already renders millisecond
precision, which is exactly what sent-at/answered-at want.

### The visual language is already decided — Minted Geometry

This branch reskinned neo-00's frontend. **Do not invent a look for the simulator, and do not
import the sidecar's dark/neon-orange.** That orange existed as a *name tag* to tell a separate
window apart from a module UI (CLAUDE.md §15); inside neo-00 there is no second window to
distinguish, and the platform already has its own voice.

- **Theme:** `frontend/src/design-system/theme/minted.css`, imported by `src/main.jsx`. Its header:
  *"Neo-00's standalone brand direction: Minted Geometry. Deliberately local to this frontend.
  Module copies of the design system are untouched."*
- **Voice**, from `styles.css:295-297`: **struck gold, absolute dark, hard edges.**
- **Tokens** — use these names, never a literal: `--neo-ink` `#0f1115` · `--neo-ink-raised`
  `#161a21` · `--neo-ink-soft` · `--neo-gold` `#ffc53d` · `--neo-gold-hover` · `--neo-line`
  `#2a303b` · `--neo-muted` · `--neo-paper` · `--neo-red` · `--neo-blue`. They are already wired
  into every `--ds-*` hook, so a component built from the barrel is correct by default.
- **No hex anywhere in app code.** DESIGN.md §8: *"do not … write a hex colour anywhere in your
  app."* `styles.css`'s own header says everything visual comes from the system, and that anything
  three screens need belongs in the design system rather than here.
- **Follow the existing per-screen shell convention** — `landing-shell`, `customer-shell`,
  `login-shell` each scope their `.ds-shell__main`. If the simulator needs shell-level tweaks it
  gets `simulator-shell`, appended to the `NEO-00 / MINTED GEOMETRY` section of `styles.css`, not
  a new file.
- The gold is an **accent**, not a fill: it is the rule above a card (`.ds-card::before`), the
  active tab underline, the focus ring, `::selection`, and the primary button. Statuses keep their
  own tones — a status you have to think about is a status you misread.

**No new dependencies.** DESIGN.md §8 forbids them and `package.json` has exactly `react` +
`react-dom`. The one genuine gap is a JSON editor: compose `Textarea mono` + `try { JSON.parse() }`
driving `Field error` / `Textarea invalid`, and `CodeBlock value={obj}` (which pretty-prints) for
display. No CodeMirror, no Monaco. In-repo precedent worth ten minutes first:
`neo-05/frontend/src/components/WhatIfSimulatorScreen.jsx`.

`maxRows`: neo-00 is the documented exemption to the 10-row cap, and this log is generated by the
operator's own clicks — `maxRows={50}` is legitimate here, with a comment saying a module must not
copy the line.

**One free bonus:** a second button that POSTs the scenario body to the existing
`POST /api/v1/applications` and **starts a full journey** from the corpus. Zero new backend code, and
a better demo driver than the random generator.

---

## Part C — docs

- `README.md:268-273` explains the sidecar's separation as *"scaffolding does not belong inside a
  graded deliverable."* That reasoning is about **module** repos; neo-00 is instructor-owned and
  ungraded. Say so, and say what each tool is for: sidecar = the orchestrator you can run offline;
  simulator = the orchestrator.
- `AGENTS.md:30` — *"a change has to be made in the orchestrator, the sidecar and the template
  together"* gains a carve-out: the simulator is orchestrator-only and adds no wire surface.
- `api-contract.md` §5 — the `/simulator/**` row (A7).
- `../CLAUDE.md` — new §22.
- **Tell team 01**, separately: their committed `ORCHESTRATOR_URL`, in *both* `docker-compose.yml`
  and `backend/src/main/resources/application.yml`, points at the dev ALB root while all nine other
  modules point at their sidecar — and their own `.env.example`, `README.md` and `AGENTS.md` still
  say otherwise. Their callbacks are vanishing into the real orchestrator today. Their repo, their
  fix (§20).

---

## Verification

1. `./mvnw test`, then `./mvnw verify -DskipITs=false`. Existing 31+3 stay green. **Prove the new
   tests fail without the change**, especially A4's "no `application_event` for a simulated report".
2. `npm run build` clean; `npm run dev` against a local backend. Confirm the endpoint really is
   proxied — a path outside `/api/` would return index.html with `200`, which looks like success.
3. `docker compose up` from a clean volume: `005` applies, `simulation` exists, `SERVICE_10_URL`
   resolves. Then **restart over the existing volume** to replay the real upgrade path — a green
   build never proves a migration is deployable.
4. End to end locally: dispatch SIM-01 at neo-01 → `202`, then its report pairing back as
   `PASSED → ACCEPTED` (neo-01's own word, canonicalised — A5 proving itself). Send all 26 →
   25×202 + SIM-26 `400`. Send one at neo-10 and get an ack from a module nothing has ever called.
5. **The isolation check — on a report that actually happened**, not just on coexistence. Snapshot
   `GET /api/v1/services`, dispatch one simulation, wait for the module to report, then assert:
   `GET /api/v1/events?serviceId=<target>` contains **zero rows** for that `applicationId`, the
   board does not list it, and `serviceSummaries()` is identical to the snapshot. Running a real
   journey alongside would pass even if the fall-through were wrong; this is the requirement.
6. Read-back: `GET /api/v1/applications/SIM-01-neo05-3` returns the §4 object; the **bare** board
   does not list it; and `?name=nowak` deliberately does **not** (A4).
7. Point a local module at the dev ALB with an id from its own sidecar → still one WARN, still no
   row anywhere. That is the stray-report case A3 chose to keep dropping.
8. Dispatch at a parked module (scale one to 0) → status `0` with a reason inside the 3s connect
   timeout, rather than a hung page.
9. On dev after deploy: one dispatch at each of the ten targets — also the fastest "are all ten
   containers up" check that exists — then one real journey to `COMPLETED`.
10. `SHOW STATUS LIKE 'Threads_connected'` / `Max_used_connections`. The pool is unchanged at 5 and
    no new pool is added, so this should not move; the ceiling is 60 and the budget is already 35.
