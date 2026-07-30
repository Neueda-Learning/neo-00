# neo-bank onboarding — API contract

**One orchestrator (`neo-00`) and ten modules (`neo-01` … `neo-10`), one per team.** This is
the whole wire: every field that crosses a repository boundary is here, and nothing else
does. The application payload is the hackathon's real one, and each module **models it as
typed records** rather than digging through a map.

The *logic* inside a module is deliberately a placeholder when a team receives it: it logs,
writes one row, and answers `ACCEPTED`. Replacing that placeholder with real business rules
is the work. **The contract is not.**

---

## 1. The sequence

```
neo-00 ──POST /api/v1/applications──▶ neo-01
            ◀──────── 202 in-progress ────
                  (decides off-thread)
            ◀──PUT /api/v1/applications/{id}    {status: ACCEPTED}
   wait 1s
       ──POST /api/v1/applications──▶ neo-02
            … and so on through neo-08, or until someone does not say ACCEPTED
```

The sequence itself is a list in the orchestrator's `application.yml`; environments override
only the base URLs. A module never learns its own step number and must not depend on one —
the order is exchangeable, and every module receives the **whole application** and reads the
fields it needs.

**The journey is eight steps: `neo-01` … `neo-08`.** `neo-09` and `neo-10` are the analytical
modules — they observe the journey rather than sit in it, so the orchestrator never dispatches
to them and they never receive a `POST /api/v1/applications`. They read the journey instead;
see §5.

- The orchestrator dispatches **one service at a time** and **waits for the status update**
  before moving on — the `202` only acknowledges receipt.
- Between a status update and the next dispatch it waits **1 second**.
- **Only `ACCEPTED` advances the journey.** `REJECTED` ends it as `REJECTED`. `REFERRED`
  stops automatic processing for human review; the same current service may later resolve it
  with `ACCEPTED` (resume) or `REJECTED` (close). Until then, no later step is dispatched.
  Your module's own word for those outcomes works too — see §3.
- No status update within **30 seconds** → the step is recorded as `TIMEOUT` and the application
  ends as `FAILED`. Late updates against a finished application are logged and ignored.
- Many applications are in flight at once; each *individual* journey is strictly sequential.

---

## 2. Orchestrator → service

```
POST http://neo-01:8080/api/v1/applications
Content-Type: application/json
```

```jsonc
{
  "applicationId": "APP-0001",
  "correlationId": "8f14e45f-ea6d-4b1c-9a3e-2b7c1d5e9a01",
  "command": "process-application",
  "application": { /* §4 */ },
  "outputs": { "approvedLimit": 3000, "apr": 24.9 }
}
```

`outputs` is what **earlier steps produced** — see §3. It is empty on step 1 and stays empty for
as long as nobody reports anything, so a module that ignores it is unaffected. Note it is a
**sibling of `application`, never merged into it**: the application is what the customer
submitted and is the same object however you obtain it, pushed here or pulled from
`GET /api/v1/applications/{id}` (§5).

### Response — `202 Accepted`, immediately

```jsonc
{
  "status": "in-progress",
  "applicationId": "APP-0001",
  "serviceId": "neo03",
  "command": "process-application"
}
```

The service also writes **exactly one log line** per received application:

```
RECEIVED APP-0001 corr=8f14e45f applicant='Maria Nowak' product=CREDIT_CARD_REWARDS limit=3000 channel=MOBILE_APP
```

---

## 3. Service → orchestrator (the status update)

Sent as soon as the service has decided — off the request thread, so milliseconds after the
`202` unless its rules call something slow.

```
PUT ${ORCHESTRATOR_URL}/api/v1/applications/{applicationId}
```

```jsonc
{
  "serviceId": "neo03",
  "status": "ACCEPTED",
  "comment": "hello world from processApplication"
}
```

**The application id is in the URL, not the body.** This is an update to an application the
orchestrator already owns, so the id identifies the resource; carrying it twice would only create
a way for the two to disagree. An `applicationId` left in the body is ignored.

| Field | Notes |
|---|---|
| `serviceId` | `neo01` … `neo10` — your repo name **without the hyphen** (`neo-04` → `neo04`) |
| `status` | **send `ACCEPTED` · `REJECTED` · `REFERRED`** — the same three for every module. Your brief's own word is also understood; see *Which vocabulary to use* |
| `comment` | free text, shown in the event log — your module's reason for the outcome |
| `outputs` | **optional.** What you *produced*, for the steps after you — see *Handing something to the next step* |

### Handing something to the next step

`comment` says **why**; `outputs` carries **what you produced**. It is an optional map beside your
status:

```jsonc
{
  "serviceId": "neo05",
  "status": "ACCEPTED",
  "comment": "granted at the product maximum",
  "outputs": { "approvedLimit": 3000, "apr": 24.9 }
}
```

The orchestrator merges it into the journey's accumulated map and sends that whole map on **every
later dispatch**, beside the application (§2). That is how neo-05's approved limit reaches neo-06,
neo-07 and neo-08. It is also shown to the operator on the board, and served on
`GET /api/v1/applications/{id}/journey`.

**Omit it entirely if you have nothing to hand on** — most modules do, and most of the time.
**Absent means unchanged**: it does not clear what earlier steps reported. An empty `{}` is a
report of nothing, which is not the same thing. Send identifiers and numbers, not documents:
the accumulated map is capped at 2000 characters, and an update that would exceed it is **dropped
whole** (never truncated — half a JSON document would reach the next module looking like data)
with a `WARN` naming your module.

#### Who may write which key

The merge is last-writer-wins and **will not defend itself**. If two modules wrote
`approvedLimit`, the later step would silently overwrite the earlier and the card would be
embossed against the wrong number. So the keys are owned:

| key | written by | type | meaning |
|---|---|---|---|
| `approvedLimit` | **`neo05` only** | integer, whole GBP | the limit actually **granted** — not `product.requestedCreditLimit`, which is what the applicant asked for and is already in the application |
| `apr` | **`neo05` only** | number, one decimal | the rate the agreement is priced at |
| `accountId` | **`neo07` only** | string | the card account opened in the core, e.g. `CC-0058291` |
| `accountReference` | **`neo07` only** | string | neo-07's own case reference, e.g. `acc-a1b2c3d4` |
| `panLast4` | **`neo08` only** | string | last four digits of the issued card |

Writing a key you do not own, or inventing one, is a change to this table first. Reading any of
them is free.

#### Reading a number back

A value crosses JSON twice on its way to you, and **JSON has one number type where Java has
several**. Whether `approvedLimit` arrives as an `Integer`, a `Long` or a `Double` depends on its
magnitude and on Jackson's mood, so a cast is a `ClassCastException` waiting for a big enough
limit:

```java
// NO — works until it doesn't
Integer limit = (Integer) outputs.get("approvedLimit");

// YES — read through Number, which every JSON numeric maps to
Object raw = outputs.get("approvedLimit");
Integer limit = raw instanceof Number n ? n.intValue() : null;
```

Same for `apr` with `doubleValue()`. Strings are safe to cast, but `instanceof String` costs
nothing and a missing key is `null` either way.

### Which vocabulary to use

**Send the shipped three.** They are the same for all ten modules whatever the topic, they are
what the skeleton already sends, and they are what the board and the service tallies are built
out of.

But the module briefs were written before this contract was simplified, so a team can reasonably
arrive at a different word — its brief's `completed` / `application-manual`, or its own domain
word like `PASSED` or `CLEAR`. **The orchestrator understands all three vocabularies**, because
how a module's outcome maps onto the journey is the orchestrator's business, not something ten
teams should each decide. It is one table in one repo —
`backend/src/main/java/com/neobank/orchestrator/saga/StatusVocabulary.java` — rather than ten
opinions in ten repos.

If your module already speaks its brief's word, it works. You do not need to change it.

### What the orchestrator accepts

Matching ignores case, and treats `-`, `_` and a space as the same separator: `application-manual`,
`APPLICATION_MANUAL` and `Application Manual` are one word.

**From every module:**

| you send | the journey |
|---|---|
| `ACCEPTED` · `COMPLETED` · `APPROVED` | advances to the next step |
| `REJECTED` | ends as `REJECTED` |
| `REFERRED` · `APPLICATION-MANUAL` · `LOCAL-MANUAL` | stops as `REFERRED` for human review; the same service may resolve it |
| `IN-PROGRESS` · `PENDING` | keeps waiting — see *Reporting progress* |

**From your module specifically**, taken from your brief's *Status mapping* row:

| serviceId | advances | ends `REJECTED` | refers for review |
|---|---|---|---|
| `neo01` verification | `PASSED` | `FAILED` | `REVIEW` |
| `neo02` policy | `APPROVED` | `REJECTED` | `REFERRED` |
| `neo03` kyc | `VERIFIED` | `FAILED` | `REVIEW` |
| `neo04` screening | `CLEAR` | `HIT` | `REVIEW` |
| `neo05` credit | `APPROVED` | `DECLINED` | `REFERRED` |
| `neo06` agreement | `SIGNED` | `DECLINED` | `EXPIRED` |
| `neo07` account | `OPENED` | — | `FAILED` |
| `neo08` card | `ISSUED` | — | `FAILED` |
| `neo09` support | `RESOLVED` · `CLOSED` | — | — |
| `neo10` analytics | *(the global set above)* | | |

### Why `FAILED` is keyed per module

Because it does not mean the same thing twice. For `neo01` and `neo03` it is a business answer —
the applicant failed a rule — so the journey ends `REJECTED`. For `neo07` and `neo08` it is not a
rejection at all: the core banking system or the card bureau was unreachable, nobody was refused,
and a person retries — so the journey stops `REFERRED` until that service reports the person's
final `ACCEPTED` or `REJECTED` decision.

One global word→status table would silently reject applicants whose card bureau had a bad minute.
That is why the table above is per module, and why it should not be flattened into one list.

### Reporting progress

`IN-PROGRESS` and `PENDING` are recorded but **do not** advance or end the journey — useful when
you have genuinely parked, like `neo06` waiting for a signature before it can say `SIGNED`.

Each progress report **restarts the 30-second clock**. So send one when something real has
happened and then send your decision; do not build a polling loop, because a module that reports
progress forever keeps its application alive forever.

#### The one exception: a wait that belongs to a customer

Thirty seconds is how long a *module* may think. It is an absurd amount of time in which to read a
credit agreement, so `neo06` — and only `neo06`, named by `orchestrator.signature.service-id` — gets
a different clock when it reports progress: the journey is marked **awaiting signature** and
measured against `orchestrator.signature.timeout` (10 minutes) instead. An `AWAITING_SIGNATURE` row
is added to the event log and `awaitingSignature: true` appears on the board row and on `/journey`,
which is how the customer's page knows to show the agreement and a Sign button.

The mark is cleared by the module's next report, whatever it says. **The rope is longer, not
infinite** — an unbounded wait is a row that sits `IN_PROGRESS` for the life of the database, which
is the failure the sweeper exists to prevent.

> ⚠️ **Our clock wins, and it is shorter than the module's own.** `neo06`'s envelope expiry is
> measured in days. Past the ten minutes the orchestrator fails the journey while the module's case
> is still `PENDING` and would still accept a signature — knowingly orphaned. The customer's page
> only offers a Sign button while the journey is still running, so nobody is shown a dead button;
> an operator looking at `neo06`'s own screens may still see a live-looking case.

This is the one place where holding at a step is correct rather than a bug. Everywhere else, a
module that goes quiet is a module that has failed.

### A word the orchestrator does not know

It is recorded on the event log, but the journey does not advance and the sweeper fails it at 30
seconds. The orchestrator logs a `WARN` naming your module and listing every word it would have
accepted — grep your orchestrator logs for `Unknown status` if a journey stalls at your step.

The event log stores the canonical word, so if you want your own word visible on the board, put
it in `comment`.

`status` comes from your own `ApplicationService.processApplication()`. The skeleton answers
`ACCEPTED` unconditionally, so **every journey completes out of the box** — which means a
journey that does *not* complete is a genuine fault rather than a module having an opinion.

> To see `REJECTED` or `REFERRED` on the board, write a rule. The orchestrator's generated
> applicants vary (income, age, country, product, document), so one real rule produces
> outcomes that vary *and* can be explained — which is the point, and which a random number
> could never do.

Response is `200 {"received": true, "applicationId": "APP-0001"}`. A service that cannot reach the
orchestrator logs a warning and drops the update — the orchestrator's timeout sweeper is what
notices.

---

## 4. The Application object

The orchestrator owns and generates this (seed 42). Money is whole GBP, dates `YYYY-MM-DD`,
timestamps UTC, countries ISO 3166-1 alpha-2 — same rules as the hackathon contract.

```jsonc
{
  "applicationId": "APP-0001",
  "channel": "MOBILE_APP",                    // WEB | MOBILE_APP | BRANCH | AGGREGATOR
  "submittedAt": "2026-07-22T09:14:00Z",

  "applicant": {
    "fullName": "Maria Nowak",
    "dateOfBirth": "1996-04-11",
    "email": "maria.nowak@example.com",
    "mobile": "+447700900123",
    "nationality": "PL",
    "countryOfResidence": "GB",
    "taxResidencies": ["GB"],
    "residentialStatus": "RENTING",           // OWNER | MORTGAGE | RENTING | LIVING_WITH_FAMILY | OTHER
    "currentAddress": { "line1": "42 Hanbury Street", "line2": null,
                        "city": "London", "postcode": "E1 5JP", "country": "GB" },
    "monthsAtAddress": 14,
    "dependants": 0
  },

  "identityDocument": {
    "type": "PASSPORT",                       // PASSPORT | DRIVING_LICENCE | NATIONAL_ID
    "documentId": "ZS1234567",
    "issuingCountry": "PL",
    "expiryDate": "2031-02-28"
  },

  "employment": {
    "status": "PERMANENT",                    // PERMANENT | CONTRACT | SELF_EMPLOYED | STUDENT | RETIRED | UNEMPLOYED
    "employerName": "Trellis Health Ltd",
    "monthsInEmployment": 11
  },

  "finances": {
    "annualIncome": 34000,
    "monthlyHousingCost": 1000,
    "existingCreditCommitments": 180
  },

  "product": {
    "productCode": "CREDIT_CARD_REWARDS",     // _STANDARD | _REWARDS | _STUDENT
    "requestedCreditLimit": 3000
  },

  "delivery": { "useCurrentAddress": true, "address": null },

  "consents": { "termsAccepted": true, "paperlessStatements": true, "marketingConsent": false }
}
```

The service models this as typed records (`integrations/orchestrator/Application.java`) but reads
only what it needs. **Dates and codes are `String` there on purpose** — the orchestrator can send a
malformed date or an unknown product code, and the module is expected to report that rather than
refuse the request with a `400`.

---

## 5. Orchestrator API (what the front end uses)

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/applications` | create one application now (body optional — a fixture is generated) |
| `GET /api/v1/applications` | board rows: id, applicant, product, ten step statuses, overall |
| `GET /api/v1/applications?name=` | **§4 application objects** whose applicant name contains `name` (substring, case-insensitive; blank matches nothing) |
| `GET /api/v1/applications/{id}` | **the §4 application object** — the same one the dispatch envelope carries. `404` if unknown |
| `GET /api/v1/applications/{id}/journey` | the board row + the application + one entry per step + its full append-only event log |
| `GET /api/v1/events?serviceId=&limit=` | the event log filtered to one service |
| `GET /api/v1/services` | per service: in-progress count + count per status |
| `GET /api/v1/products` | the live product catalogue, proxied from `neo01` (below) |
| `PUT /api/v1/customers/{code}` | sign in as `AB12` — creates the code if new, returns `{customerId, isNew, items[]}` (below) |
| `GET /api/v1/customers/{code}` | what that customer has; `404` if the code has never been used |
| `GET /api/v1/applications/{id}/agreement` | the customer's view of their credit agreement, proxied from `neo06` (below) |
| `GET /api/v1/applications/{id}/agreement/document` | that agreement as a PDF |
| `POST /api/v1/applications/{id}/agreement/sign` · `/decline` | report what the customer did with it |
| `POST /api/v1/applications/{id}/support-case` | open a support case, proxied to `neo09` (below) |
| `GET /api/v1/generator` · `POST /api/v1/generator` | the start/stop toggle `{enabled, intervalMs}` |
| `GET /api/v1/demo-mode` · `POST /api/v1/demo-mode` | demo stepping `{enabled, parked}` (below) |
| `POST /api/v1/applications/{id}/proceed` | send the step a parked journey is waiting on (below) |
| `PUT /api/v1/applications/{id}` | where services report their status back (§3) |
| `/api/v1/simulator/**` | **instructor tooling, not module contract** — modules must never call it |
| `GET /health` · `GET /info` | ops |

Overall application status ∈ `IN_PROGRESS · COMPLETED · REJECTED · REFERRED · FAILED`.

### Reading an application back (what a service may call)

Most of the table above is the front end's business, but two rows are not:
**`GET /api/v1/applications/{id}`** and **`?name=`** are there for the ten services. A module that
needs applicant data it correctly did not store locally reads it here, live, and stores nothing.

Both answer in the **§4 application object** — identical to the `application` field of the dispatch
envelope. One object, two ways to get it: pushed to you, or pulled by you. That is the whole point;
if the pulled copy had a different shape, it would be a second contract to keep in step.

The **sidecar serves both**, byte for byte, so the flow is testable on one laptop.

> ⚠️ **One URL, two shapes.** `GET /api/v1/applications` returns *board rows* bare and *application
> objects* with `?name=`. That is a wart. The bare collection is a UI view that predates the
> search; making the collection contract-shaped means moving the board to its own path, which is
> not worth breaking the board screen for mid-hackathon. Read `{id}` and `?name=` as the contract
> surface and the bare list as the front end's.

### Demo stepping (nothing here changes what a module does)

`POST /api/v1/demo-mode {"enabled": true}` makes the orchestrator **hold every application before
every dispatch** — the first included — instead of sending it. A held journey stays `IN_PROGRESS`,
gains an `AWAITING_OPERATOR` row in its event log, and carries `pendingStep` (the step it is waiting
to send) on both the board row and `/journey`. `POST /api/v1/applications/{id}/proceed` sends that
step; `409` if the application is not held. Switching the toggle off releases everything held.

It exists so the journey can be narrated live at the speed of a person talking.

> **The button releases a dispatch. It never answers on a module's behalf.** Every service still
> receives the same §2 envelope, still decides for itself, and still reports with the same §3 `PUT`.
> Only *when* it is asked changes — so what an audience sees is the real journey slowed down, not a
> puppet of one. **No module needs to know this feature exists**, and nothing in §2, §3 or §4 moves.

A held journey is also **exempt from the callback timeout**: it is silent because nobody was asked,
not because a module went quiet. Without that exemption every demo would die 30 seconds into its
first pause, looking exactly like a broken module.

A held journey is also **exempt from the callback timeout**: it is silent because nobody was asked,
not because a module went quiet. This is a true exemption, unlike the signature hold in §3, which
only gets a longer clock — there, a module *has* been asked and could still have failed.

Every dispatch, ack, status update, timeout and journey transition is appended to
`application_event` and **never updated or deleted** — that table is the system of record.

### The four proxied endpoints (the orchestrator as a client)

Everywhere else the orchestrator is the thing modules talk to. These four are the reverse: a
customer's browser needs something a module owns, and it goes through here.

**It is one hop, and it is deliberate.** A browser could call those modules directly, but then the
page needs each module's address, every module's CORS policy has to admit this origin, and on AWS —
where each module sits behind its own path prefix on a shared load balancer — the addresses are
different again. Proxying keeps §1's rule that the orchestrator is the only door.

| Route | Goes to | Notes |
|---|---|---|
| `GET /api/v1/products` | `neo01` `GET /products` + `/products/{code}/versions` | only rows the module marks `current` **and** `active`; `[]` when it is unreachable, and the picker falls back to its own copy |
| `GET …/agreement` | `neo06` `GET /cases/{id}` | **projected**, not passed through — terms and status only, no envelope id or operator timeline |
| `GET …/agreement/document` | `neo06` `GET /cases/{id}/document` | `application/pdf`, `Cache-Control: no-store` |
| `POST …/agreement/sign` · `/decline` | `neo06` `POST /cases/{id}/signature-events` | the envelope id is read from the case here, never sent by the browser |
| `POST …/support-case` | `neo09` `POST /api/v1/support/execute` | body `{category, description}`; the id, correlation id and application are added here |

Three things worth knowing:

- **None of them decides anything.** Signing reports a fact to `neo06`; whether the journey moves is
  `neo06`'s answer, arriving on the ordinary §3 `PUT`. The orchestrator never advances a journey on
  the strength of a button.
- **`neo09` is addressed, not sequenced.** It is an analytical module and is deliberately absent from
  `orchestrator.services[]`, so nothing is ever dispatched to it. `SERVICE_09_URL` exists only for
  this one call.
- **One support case per application.** `neo09` derives its case id from the correlation id, so a
  second request returns the first case and the `202` carries no new reference.

A module's own refusal keeps its status and its sentence (`404` unknown, `409` already decided or
expired). A module failing or being unreachable becomes a `502` in words a customer can read, never
a status code.

### Who applied — `?customerId=AB12`

A customer signs in on the customer surface with a four-character code, two letters and two
digits. `POST /api/v1/applications?customerId=AB12` records it **on the application row**, which
is what makes "your products" possible at all.

**A query parameter and deliberately not a field of the §4 application object.** Adding a key
there would be additive rather than breaking — but every module binds that object into a typed
record, and the orchestrator cannot verify from here that all ten of them, the sidecar and the
template tolerate one they have never seen. **Nothing about this reaches a module**: the payload is
stored exactly as it was sent, and a test asserts the code is absent from it.

The rule has no exception — the parameter names the customer whatever the body, including on the
no-body fixture path, which is how a customer's history is seeded without filling the form eight
times. Omitted, the application belongs to nobody, which is right for the generator and for the
backoffice's **+ one**. An unknown code is a `404`, never a silent create.

> ⚠️ **This is identification, not authentication, and nothing is authorised by it.** There is no
> password, so "that code is taken" and "that code is yours" are the same fact. The code decides
> which applications a customer's own screen *lists*; `/journey`, `/agreement`, `/agreement/sign`
> and `/support-case` all still key on the application id alone and check no ownership — anybody
> holding an id can read and act on it. That is fine for a single-user demonstration stack, and it
> is written down so nobody mistakes the login screen for a security boundary.

An application becomes a **product** once `currentStep` is past the signature step **and** the
journey is `COMPLETED` or still `IN_PROGRESS`. The liveness half is not optional: `currentStep` is
never rewound, so a journey that dies at step 7 or 8 keeps that number for ever and a plain "past
step six" test would show somebody an account number that was never opened.

---

## 6. Service API

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/applications` | the contract entry point (§2) |
| `GET /api/v1/applications` | the rows this service stored + the status it answered — read by its own UI, never by the orchestrator |
| `GET /health` · `GET /info` | DB-backed health · identity, BIAN domain, mocked dependencies |

A service is free to add endpoints its operator screen needs. Only the `POST` shape is fixed.

Each service owns its own MySQL schema (`neo_01`…`neo_10`) and never reads another's.
