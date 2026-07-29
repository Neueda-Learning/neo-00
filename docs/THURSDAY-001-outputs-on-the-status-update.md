# `outputs` is built — a step can hand something to the steps after it

*2026-07-29 · affects neo-05, neo-06, neo-07, neo-08 · everyone else can stop reading after Part 1*

Wednesday's briefing listed this under **"Planned, not built yet"**. It is now built, in the
orchestrator, in the sidecar, and on both sides of the two modules that had the numbers and
nowhere to put them.

---

## Part 1 — everyone: nothing you have breaks

Your status update was three fields. It still works, unchanged, and if you have nothing to hand
to a later step you should keep sending exactly what you send today.

```jsonc
{ "serviceId": "neo03", "status": "ACCEPTED", "comment": "why" }
```

There is now an optional fourth. **Absent means unchanged** — it does not clear anything, it is
not validated, and six of the eight modules have no reason to send one.

The envelope you receive also gained a field. If you do not read it, nothing changes:

```jsonc
{
  "applicationId": "APP-0001",
  "correlationId": "…",
  "command": "process-application",
  "application": { /* §4, exactly as before */ },
  "outputs": { "approvedLimit": 2500, "apr": 24.9 }
}
```

`outputs` is a **sibling** of `application`, never inside it. The application is what the customer
submitted and reads the same whether it is pushed to you here or pulled from
`GET /api/v1/applications/{id}`. If those two ever disagreed, pulling would be worthless.

Full spec: **`api-contract.md` §3**, *Handing something to the next step*.

---

## Part 2 — the four modules this is for

### Who owns which key

The orchestrator merges every module's map into one and **last writer wins**. It will not defend
itself. If two modules wrote `approvedLimit`, the later step would silently overwrite the earlier
one and the card would be embossed against the wrong number. So the keys are owned:

| key | written by | read by | type |
|---|---|---|---|
| `approvedLimit` | **neo-05 only** | neo-06, neo-07 | integer, whole GBP |
| `apr` | **neo-05 only** | neo-06 | number, one decimal |
| `accountId` | **neo-07 only** | neo-08 | string, `CC-0058291` |
| `accountReference` | **neo-07 only** | neo-08 | string, `acc-a1b2c3d4` |
| `panLast4` | **neo-08 only** | — | string |

Writing a key you do not own, or inventing one, is a change to that table first. **Reading any of
them is free.**

### neo-05 — done, nothing for you to do

Your `grantedLimit` and `apr` were on `credit_record` all along with no way out. `ACCEPTED` now
reports both. A rejection sends nothing, and a replay rebuilds the map from the stored row so a
duplicate request hands over exactly what the original decision did.

> One thing that is **not** part of this and is still yours:
> `OrchestratorClient.resolveApplicationIdsByName` expects `List<String>`, and
> `GET /api/v1/applications?name=` returns a list of **application objects**. That call returns
> nothing today either way. One line.

### neo-06 — the wall is down

Your `AgreementRecord` already has `approved_limit` and `apr`, javadoc'd *"Copied from
`outputs.approvedLimit`"*. That block now exists on the envelope. **This is what your PDF was
waiting for** — it says `"hello world"` because there was nothing to price it from.

Build order still matters: the `AgreementConfig` seed is the Day-0 item you skipped, and
`termsVersion` / the expiry window / the minimum-payment rule all hang off it.

### neo-07 — done, nothing for you to do

Two halves closed. You opened every account for `product.requestedCreditLimit` — what the
applicant *asked* for — because `AccountOpeningEngine`'s `CreditTerms` branch had no source and
the one caller passed `CreditTerms.none()` unconditionally. Half that engine was unreachable and
`creditAmountFallback` was `true` on every case ever recorded. It now opens for neo-05's
`approvedLimit` when there is one, and the flag finally distinguishes a decision from a guess.

And you report `{accountId, accountReference}` on `ACCEPTED` — from the automatic open, the
replay, and the operator's retry in the failed-opens queue, all three.

### neo-08 — one small change, and it is yours

`CardRecord.account_id` is a column with **no writer**. `outputs.accountId` is now on your
envelope; writing it is a one-liner in your intake.

Worth knowing: your `CardExecuteRequest` already declares
`(applicationId, correlationId, command, application, outputs)` — you guessed the shape exactly
right. But it sits on `/api/v1/card/execute`, and **the orchestrator calls
`/api/v1/applications`**, which is your `ApplicationController` + `ApplicationRequest`. The field
has to go on the record that is actually used.

---

## Part 3 — reading a number back, which is where this will bite

A value crosses JSON twice on its way to you, and **JSON has one number type where Java has
several**. Whether `approvedLimit` arrives as an `Integer`, a `Long` or a `Double` depends on its
magnitude and on Jackson's mood.

```java
// NO — works until it doesn't, then throws ClassCastException in a worker thread
Integer limit = (Integer) outputs.get("approvedLimit");

// YES — every JSON numeric maps to Number
Object raw = outputs.get("approvedLimit");
Integer limit = raw instanceof Number n ? n.intValue() : null;
```

`neo-07/…/orchestrator/ApplicationRequest.approvedLimit()` is a worked example. Same for `apr`
with `doubleValue()`. Treat every key as optional: nothing has been reported before step 5, and a
declined application never gets a limit at all.

---

## Part 4 — testing it on your own laptop

**`docker compose up --build sidecar`** to pick up the new sidecar. It stores and displays the
`outputs` you send, in a new **Produced** column on the exchange log — the half carrying your own
data was previously the half you could not see.

**Send `SIM-27`** to exercise the *reading* half. It is SIM-01 with neo-05 having already decided,
and the only scenario with an `outputs` block. The sidecar cannot produce one by sequencing: it
has no journey and no later step, which is exactly why the scenario has to ship one.

> **Its `approvedLimit` is 2500 against a requested 3000, deliberately.** A module that quietly
> reads `product.requestedCreditLimit` still looks right until you read the number. Open the
> account, price the agreement and emboss the card for **2500**.

---

## Limits, so you find them by reading rather than by debugging

- The accumulated map is capped at **2000 characters**. An update that would exceed it is
  **dropped whole** — never truncated, because half a JSON document would reach the next module
  looking like data. The orchestrator logs a `WARN` naming your module. Send identifiers and
  numbers, not documents.
- An update from a service that is **not the current step** is recorded on the event log and
  changes nothing, including the map.
- An `IN-PROGRESS` report **does** merge, so neo-06's `PENDING` call can carry something on the
  way past.
- `{}` is not the same as absent. `{}` says you considered the question and produced nothing;
  absent says nothing at all. Send absent.
