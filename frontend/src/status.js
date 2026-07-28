// One place that decides what a status MEANS, so the board dots, the service
// panels and the event log can never disagree about what "REFERRED" is.
//
// The design system knows no business words on purpose (design-system/DESIGN.md
// § "Tones") — every component takes one of five tones, and this file is the only
// translation between the orchestrator's vocabulary and them.
import { TONES, toneMapper } from './design-system';

export const STEP_PENDING = 'pending';
export const STEP_IN_FLIGHT = 'in-flight';

/** A single step's answer, as it appears on the board's dot trail. */
export const stepTone = toneMapper(
  {
    [STEP_PENDING]: TONES.NEUTRAL,
    [STEP_IN_FLIGHT]: TONES.INFO,
    ACCEPTED: TONES.POSITIVE,
    REJECTED: TONES.NEGATIVE,
    REFERRED: TONES.WARNING,
    TIMEOUT: TONES.NEGATIVE,
    // Colours the event-log badge on a PROGRESS_REPORTED row — a service saying it is still
    // working. No board dot can carry it: the orchestrator records progress under its own event
    // type precisely so the step's in-flight dot survives.
    IN_PROGRESS: TONES.INFO,
  },
  TONES.NEUTRAL
);

/** The journey's own outcome. */
export const journeyTone = toneMapper(
  {
    IN_PROGRESS: TONES.INFO,
    COMPLETED: TONES.POSITIVE,
    REJECTED: TONES.NEGATIVE,
    REFERRED: TONES.WARNING,
    FAILED: TONES.NEGATIVE,
  },
  TONES.NEUTRAL
);

/** An event line: its status if it has one, otherwise what kind of event it was. */
export function eventTone(event) {
  if (event.status) return stepTone(event.status);
  if (event.eventType === 'TIMEOUT' || event.eventType === 'DISPATCH_FAILED') {
    return TONES.NEGATIVE;
  }
  return TONES.NEUTRAL;
}

export function time(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '—';
}

/** Millisecond precision — the point of the event feed is seeing the gaps. */
export function clock(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return `${d.toLocaleTimeString()}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

export const money = (n) => (n == null ? null : `£${Number(n).toLocaleString()}`);
