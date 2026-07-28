# 001 · Make the orchestrator lenient about status vocabulary

**Date:** 2026-07-28 (Tuesday night) · **Owner:** instructor (neo-00 only) · **Status:** implemented 2026-07-29

---

## Context

An end-to-end journey on dev (`APP-0011`) died at step 1:

```
step 1:       REPORTED APP-0011 -> FAILED
orchestrator: Status update for APP-0011 carried unknown status 'FAILED' — recorded, ignored
orchestrator: Application APP-0011 ended FAILED — timed out waiting for neo01
```

**One module's status word silences the whole journey for all ten teams.** The orchestrator ignores
what it does not recognise, and the only symptom is a 30-second timeout with no hint of the cause.

### Three overlapping vocabularies now exist

The contract was **simplified after the briefs were written** (the divergence CLAUDE.md §13/§17 tracks),
so a team can reasonably arrive at any of three shapes:

| Source | What a module sends as `status` |
|---|---|
| **Shipped wire** — `api-contract.md` §3, and the skeleton | `ACCEPTED` · `REJECTED` · `REFERRED` |
| **v3 briefs** — `project-requirements/v3/00-common-technical.md:33` | `completed` · `rejected` · `application-manual` · `local-manual` · `in-progress`, **with the domain word in a separate `outcome` field** |
| **Its own domain word** — each brief's *Status mapping* row | `PASSED` · `CLEAR` · `SIGNED` · `ISSUED` … |

Sending `FAILED` as `status` matches neither contract: under the briefs it would be
`status: "rejected"` with `outcome: "FAILED"`, and under the shipped wire it would be `REJECTED`.
But it is an entirely plausible reading of a brief that puts `FAILED` and "status mapping" on the
same line, and **the cost of that reading — a dead journey for everyone — is wildly out of
proportion to it.**

So the fix is not to police teams. It is to make the boundary lenient: CLAUDE.md §4 already says
*"Status semantics are orchestrator-owned. How each module's local outcome maps onto the saga outcome
is defined by the orchestrator, not invented per team."*

## Why the domain words must be keyed per module

`FAILED` does not mean the same thing twice:

- **neo-01 / neo-03** — `FAILED` → `rejected` → **REJECTED**. A business answer: the applicant failed a rule.
- **neo-07 / neo-08** — `FAILED` → `application-manual` → **REFERRED**. Not a rejection at all: the core
  banking system or card bureau was unreachable, so a person retries.

A single global word→status table would silently reject applicants whose card bureau had a bad minute.

## The mapping

**Accepted globally, for every module** — the shipped three plus v3's canonical set:

| incoming (case-insensitive) | → saga |
|---|---|
| `ACCEPTED` · `COMPLETED` · `APPROVED` | ACCEPTED |
| `REJECTED` | REJECTED |
| `REFERRED` · `APPLICATION-MANUAL` · `LOCAL-MANUAL` | REFERRED |
| `IN-PROGRESS` · `PENDING` | non-terminal — see below |

**Accepted per `serviceId`** — each brief's *Status mapping* row:

| serviceId | domain | → ACCEPTED | → REJECTED | → REFERRED |
|---|---|---|---|---|
| neo01 | verification | `PASSED` | `FAILED` | `REVIEW` * |
| neo02 | policy | `APPROVED` | `REJECTED` | `REFERRED` |
| neo03 | kyc | `VERIFIED` | `FAILED` | `REVIEW` |
| neo04 | screening | `CLEAR` | `HIT` | `REVIEW` |
| neo05 | credit | `APPROVED` | `DECLINED` | `REFERRED` |
| neo06 | agreement | `SIGNED` | `DECLINED` | `EXPIRED` |
| neo07 | account | `OPENED` | — | `FAILED` |
| neo08 | card | `ISSUED` | — | `FAILED` |
| neo09 | support | `RESOLVED` · `CLOSED` | — | — |
| neo10 | analytics | *(global set only — no brief mapping row)* | | |

\* **Corrected while implementing:** `REVIEW` *is* in a brief row —
`project-requirements/v5/src/spec/module-01-application-verification/module.md:119` reads
``REVIEW | application-manual | parks for a person``. It is absent only from the older v3 brief,
which is what this footnote was reading. Nothing to confirm with the team.

**Also corrected:** neo09 was lumped in with neo10 above as having no mapping row. It has one —
``RESOLVED`` and ``CLOSED`` both → `completed`, with the note *"a support case can never break the
journey"*. neo10 genuinely has none: its status table is about snapshot states (`TAKEN` / `SERVED`)
and says *"no callback ever leaves this module"*, so those two words are deliberately **not** in the
vocabulary.

Judgement calls worth knowing about: `LOCAL-MANUAL`→REFERRED (a human is involved either way; no brief
uses it in a mapping row) and `APPROVED` globally (CLAUDE.md §4 lists it as a callback status, and it is
unambiguously positive everywhere).

**`IN-PROGRESS` / `PENDING` are non-terminal** (neo-06's brief maps `PENDING`→`in-progress`): record the
event, do not advance, do not fail. `sweepTimeouts` keys on *"the last event of any kind"*, so recording
the event naturally extends the window.

> **As built, this needed more than "no special handling".** See *What changed on the way in*, §2 —
> recorded as a `CALLBACK` it would have erased the module from both screens.

## Changes

All in **`neo-00`**. No team repo is touched — including neo-01, which starts working as-is.

1. **New `backend/src/main/java/com/neobank/orchestrator/saga/StatusVocabulary.java`** — the global set
   plus `Map<serviceId, Map<word, canonical>>`, exposing `Optional<String> canonical(serviceId, word)`.
   Cite the brief each row comes from. Input arrives uppercased/trimmed via `SagaStore.normalize()`
   (`SagaStore.java:424`), so keys are uppercase and hyphenated (`APPLICATION-MANUAL`).

2. **`SagaStore.recordApplicationStatusUpdate`** (`SagaStore.java:140`) — resolve through
   `StatusVocabulary` and switch on the **canonical** result rather than the raw string
   (`SagaStore.java:167`). Keep `Ignored` for a genuinely unknown word, but make the log name the module
   and list what it accepts; today's message gives an operator nothing to act on.

3. **`api-contract.md` §3 — a real rewrite, not a one-line edit.** It currently states the opposite rule
   (*"Ten modules inventing ten vocabularies is ten things the orchestrator would have to know about"*).
   It must now document **all three accepted vocabularies**, say plainly **which one a team should use**
   (the shipped three), and state that the orchestrator owns the mapping per CLAUDE.md §4. Leaving it is
   exactly the stale-artefact failure §10 flags as highest-risk.

**Deliberately NOT in this change:** the domain word is mapped but not stored.
`ApplicationEvent.status` must keep carrying the canonical value — it feeds `steps[].status`
(`SagaStore.java:355`) and `frontend/src/status.js`'s tone map, so storing `CLEAR` there would strip the
board of its colours and push ten vocabularies onto modules 9/10. Preserving the module's own word
properly means a **new nullable `outcome` column** on `application_event` (one appended changeset, which
also converges toward `api-contract.md` §2's richer shape). Worth doing — but it is a schema change plus
a deploy and is not needed to unblock anyone. Recommend as an immediate follow-up.

## What changed on the way in

Five deltas between the proposal above and what was built. Each is a correction, not a preference.

1. **Canonicalise *before* the event insert, not only in the switch.** Change 2 named the switch at
   `SagaStore.java:167`, but the event row is written at `:149` — before every guard — and
   `ApplicationEvent.status` feeds two derived views. `toRow()` uses `put` (not `putIfAbsent`) for a
   `CALLBACK`, so it always overwrites the board dot, and `frontend/src/status.js` knows six words:
   a stored `PASSED` renders grey. `serviceSummaries()` buckets on the three literals and computes
   `total` from those buckets, so `PASSED` would land in no bucket *and* drop out of the total.
   The canonical word is therefore what gets stored; a genuinely unknown word is stored as sent,
   because there is no canonical value and that word is the operator's only clue.

2. **`IN_PROGRESS`/`PENDING` get their own event type, `PROGRESS_REPORTED`.** `inFlightByService()`
   clears `waitingOn` on *any* `CALLBACK`, so a progress report recorded as one drops the
   application out of the services screen's running count — into no bucket at all. Combined with
   the overwritten dot, a module honestly reporting progress would erase itself from both screens.
   A distinct event type lands in the `default` arm of both switches and is excluded by
   `countCallbacksByServiceAndStatus()` (which filters `where e.eventType = 'CALLBACK'`), so all
   four derived views stay correct with no change to any of them. `event_type` is `VARCHAR(24)` and
   `PROGRESS_REPORTED` is 17 characters — **no changeset**. Proved by temporarily recording it as a
   `CALLBACK`: the dot flips from `in-flight` to `IN_PROGRESS`, which renders grey.

3. **A fourth `CallbackOutcome`, `Waiting`.** `Ignored` promises "late, duplicate, or from the wrong
   service", and a module doing what its brief says is none of the three. Safe to add: the only
   consumer in the backend is `SagaEngine`'s `instanceof Advance`, and no switch over the sealed
   interface exists.

4. **Normalisation folds separators and pins the locale.** `-`, `_` and a space are one separator,
   so `application-manual` / `APPLICATION_MANUAL` / `Application Manual` are one word — matching
   what `frontend/src/design-system/tones.js` already does. `toUpperCase(Locale.ROOT)` retires a
   latent bug in the old `SagaStore.normalize`: under a Turkish default locale `in-progress`
   upper-cases to `İN_PROGRESS` and matches nothing.

5. **`ApplicationEvent.status` is capped at its column width.** The unknown-word path is *designed*
   to put arbitrary module input into a `VARCHAR(24)`; over-length input would throw on insert and
   turn the module's report into a 500, losing the very clue the warning exists to surface. The
   column is unchanged — only the constructor caps.

Also as built: the unknown-word warning now names the module, quotes the raw word, states the
consequence, and lists every word that module could have sent.

## Verification

1. **`StatusVocabularyTest` is where coverage actually comes from** — a journey only exercises the steps
   it reaches, and only with the outcome the seeded applicant happens to produce. Assert: every brief
   word for all eight modules; the global set for all ten; **`FAILED` → REJECTED for neo01/neo03 but
   REFERRED for neo07/neo08** (the case that justifies per-module keying); lower-case and hyphenated
   input; unknown words return empty.
2. `mvn verify -DskipITs=false` in `neo-00/backend` green.
3. Merge to `main`, watch `deploy · dev` → `smoke` green.
4. **Drive a real journey as a wiring check** — `POST /api/v1/applications` (empty body generates from
   the seeded fixtures), poll to a terminal state. It must get **past step 1**.
5. Board at `/` on the dev ALB still colours correctly; `steps[].status` still reads
   `ACCEPTED`/`REJECTED`/`REFERRED`.

## Follow-ups this exposes (not in scope here)

- **Nothing tests the contract end-to-end.** A journey has to be run by hand to discover a module is
  mute — no CI check dispatches to every registered module and asserts it answers. That gap is what let
  this sit undetected all day.
- The `outcome` column above.
- **Repeated progress reports pin a journey open indefinitely.** The sweeper keys on the last event of
  any kind, so a module reporting `PENDING` every 20 seconds never times out. A new hole, documented in
  `api-contract.md` §3 rather than closed; the fix if it ever bites is one age ceiling in
  `sweepTimeouts`.
- **The sidecar needs no change** — it has no state machine and always answers `200`, so a team testing
  its own word locally was never blocked by vocabulary. It also cannot *confirm* the mapping; only the
  real orchestrator can.
- **`neo10` will still time out.** Its brief says no callback ever leaves it, yet `application.yml`
  dispatches it as step 10, so no journey can complete until its skeleton answers.
