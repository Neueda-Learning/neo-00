# attempt-02 — API contract

**One orchestrator (`neo-00`) and one service (`neo-01`).** The wire is unchanged
from attempt-01 — this is the same contract with nine slots removed, so what is proven here
transfers to the full set by adding them back. The application payload is lifted from the
hackathon's real contract (`neo-capstone/api-contract.md` §3), so the envelope teams will
actually receive is rehearsed and is **modelled as typed records** in the service. The *logic*
inside the service is deliberately a placeholder: it logs, writes one row, and answers ACCEPTED.

---

## 1. The sequence

```
neo-00 ──POST /api/v1/applications──▶ neo-01
            ◀──────── 202 in-progress ────
                  (works off-thread)
            ◀──PUT /api/v1/applications/{id}    {status: ACCEPTED}
   wait 1s

(attempt-01 continued with nine more steps here. The sequence is a list in
`application.yml`, cut to one entry by the ORCHESTRATOR_SERVICES_0_* environment
variables — see the README.)
```

- The orchestrator dispatches **one service at a time** and **waits for the status update**
  before moving on — the `202` only acknowledges receipt.
- Between a status update and the next dispatch it waits **1 second**.
- **Only `ACCEPTED` advances the journey.** `REJECTED` ends it as `REJECTED`, `REFERRED`
  ends it as `REFERRED`; the remaining steps are never dispatched.
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
| `serviceId` | `neo01` … `neo10` — **note it has no `-a`**, unlike the repo name `neo-01` |
| `status` | `ACCEPTED` · `REJECTED` · `REFERRED` — the same three for every service |
| `comment` | free text, shown in the event log — the service's reason for the outcome |

`status` comes from the service's own `ApplicationService.processApplication()`. The skeleton
answers `ACCEPTED` unconditionally, so **every journey completes** and one that does not means the
wire is genuinely broken — which is what this stack exists to test.

> This used to be a seeded weighted RNG (`WEIGHT_ACCEPTED` and friends, default 70/15/15), which
> made P(reaching step 10) ≈ 2.8% and needed pinning to 100 for any demo. Those knobs are gone.
> To see `REJECTED` or `REFERRED` on the board, write logic in the service: the generator's
> applications vary, so one real rule gives outcomes that vary *and* can be explained.

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
| `GET /api/v1/applications/{id}` | one application + its full append-only event log |
| `GET /api/v1/events?serviceId=&limit=` | the event log filtered to one service |
| `GET /api/v1/services` | per service: in-progress count + count per status |
| `GET /api/v1/generator` · `POST /api/v1/generator` | the start/stop toggle `{enabled, intervalMs}` |
| `PUT /api/v1/applications/{id}` | where services report their status back (§3) |
| `GET /health` · `GET /info` | ops |

Overall application status ∈ `IN_PROGRESS · COMPLETED · REJECTED · REFERRED · FAILED`.

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
