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
            … and so on through neo-10, or until someone does not say ACCEPTED
```

The sequence itself is a list in the orchestrator's `application.yml`; environments override
only the base URLs. A module never learns its own step number and must not depend on one —
the order is exchangeable, and every module receives the **whole application** and reads the
fields it needs.

- The orchestrator dispatches **one service at a time** and **waits for the status update**
  before moving on — the `202` only acknowledges receipt.
- Between a status update and the next dispatch it waits **1 second**.
- **Only `ACCEPTED` advances the journey.** `REJECTED` ends it as `REJECTED`, `REFERRED`
  ends it as `REFERRED`; the remaining steps are never dispatched. Your module's own word for
  those outcomes works too — see §3.
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
  "application": { /* §4 */ }
}
```

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

**Three fields — the application id is in the URL, not the body.** This is an update to an
application the orchestrator already owns, so the id identifies the resource; carrying it twice
would only create a way for the two to disagree. An `applicationId` left in the body is ignored.

| Field | Notes |
|---|---|
| `serviceId` | `neo01` … `neo10` — your repo name **without the hyphen** (`neo-04` → `neo04`) |
| `status` | **send `ACCEPTED` · `REJECTED` · `REFERRED`** — the same three for every module. Your brief's own word is also understood; see *Which vocabulary to use* |
| `comment` | free text, shown in the event log — your module's reason for the outcome |

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
| `REFERRED` · `APPLICATION-MANUAL` · `LOCAL-MANUAL` | ends as `REFERRED` |
| `IN-PROGRESS` · `PENDING` | keeps waiting — see *Reporting progress* |

**From your module specifically**, taken from your brief's *Status mapping* row:

| serviceId | advances | ends `REJECTED` | ends `REFERRED` |
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
and a person retries — so the journey ends `REFERRED`.

One global word→status table would silently reject applicants whose card bureau had a bad minute.
That is why the table above is per module, and why it should not be flattened into one list.

### Reporting progress

`IN-PROGRESS` and `PENDING` are recorded but **do not** advance or end the journey — useful when
you have genuinely parked, like `neo06` waiting for a signature before it can say `SIGNED`.

Each progress report **restarts the 30-second clock**. So send one when something real has
happened and then send your decision; do not build a polling loop, because a module that reports
progress forever keeps its application alive forever.

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
| `GET /api/v1/applications/{id}/journey` | the board row + the application + its full append-only event log |
| `GET /api/v1/events?serviceId=&limit=` | the event log filtered to one service |
| `GET /api/v1/services` | per service: in-progress count + count per status |
| `GET /api/v1/generator` · `POST /api/v1/generator` | the start/stop toggle `{enabled, intervalMs}` |
| `PUT /api/v1/applications/{id}` | where services report their status back (§3) |
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

Every dispatch, ack, status update, timeout and journey transition is appended to
`application_event` and **never updated or deleted** — that table is the system of record.

---

## 6. Service API

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/applications` | the contract entry point (§2) |
| `GET /api/v1/applications` | the rows this service stored + the status it answered — read by its own UI, never by the orchestrator |
| `GET /health` · `GET /info` | DB-backed health · identity, BIAN domain, mocked dependencies |

A service is free to add endpoints its operator screen needs. Only the `POST` shape is fixed.

Each service owns its own MySQL schema (`neo_01`…`neo_10`) and never reads another's.
