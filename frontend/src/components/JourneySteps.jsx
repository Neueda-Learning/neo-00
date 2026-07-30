import React from 'react';
import { StatusDot } from '../design-system';
import { STEP_IN_FLIGHT, STEP_PENDING, stepTone } from '../status.js';

// What each step's state means to the person waiting, rather than to the bank. The keys are the
// orchestrator's own words; anything not listed falls back to the word itself, which is the right
// behaviour for a module answering something nobody has taught this page yet.
const SAYS = {
  [STEP_PENDING]: 'Not started',
  [STEP_IN_FLIGHT]: 'Checking…',
  ACCEPTED: 'Done',
  REJECTED: 'Not passed',
  REFERRED: 'Needs a look',
  TIMEOUT: 'No answer',
};

/**
 * The customer's progress down the left: one row per check the bank runs, live.
 *
 * <p>The tone of each row comes from `stepTone` in status.js — the same function the operator's
 * board uses for the same data. Two surfaces reading one journey must not be able to reach
 * different conclusions about it, and a second copy of that map is how they would.
 *
 * The step being waited on while `awaitingSignature` is true is the customer's own: it says so
 * rather than "Checking…", because at that moment the bank is not the one holding things up.
 */
export default function JourneySteps({ steps = [], services = [], awaitingSignature = false }) {
  const nameOf = (step) =>
    services.find((s) => s.step === step)?.name ?? `Step ${step}`;

  return (
    <ol className="journey-steps" aria-label="Your application's progress">
      {steps.map((s) => {
        const waitingOnYou = awaitingSignature && s.status === STEP_IN_FLIGHT;
        const label = waitingOnYou ? 'Waiting for you' : SAYS[s.status] ?? s.status;
        return (
          <li
            key={s.step}
            className={[
              'journey-steps__item',
              s.status === STEP_IN_FLIGHT && 'journey-steps__item--active',
              s.status === 'ACCEPTED' && 'journey-steps__item--done',
              s.status === 'REFERRED' && 'journey-steps__item--referred',
              s.status === STEP_PENDING && 'journey-steps__item--idle',
            ]
              .filter(Boolean)
              .join(' ')}
            aria-current={s.status === STEP_IN_FLIGHT ? 'step' : undefined}
          >
            <StatusDot tone={waitingOnYou ? 'warning' : stepTone(s.status)} />
            <span className="journey-steps__name">{nameOf(s.step)}</span>
            <span className="journey-steps__state">{label}</span>
          </li>
        );
      })}
    </ol>
  );
}
