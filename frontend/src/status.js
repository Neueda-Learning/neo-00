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
  // Demo stepping. The hold is a warning — the journey is stopped and only a person
  // restarts it; the release is just news. Neither carries a status, so without these
  // two both would render as anonymous grey on the very screen being demonstrated.
  if (event.eventType === 'AWAITING_OPERATOR') return TONES.WARNING;
  if (event.eventType === 'RELEASED_BY_OPERATOR') return TONES.INFO;
  // The agreement is with the customer. A warning for the same reason as the operator hold —
  // the journey has stopped and only a person restarts it — and it carries no status either.
  if (event.eventType === 'AWAITING_SIGNATURE') return TONES.WARNING;
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

/** The acknowledgement of a one-shot simulator POST. */
export function httpTone(status) {
  const code = Number(status);
  if (code === 202) return TONES.POSITIVE;
  if (code === 0 || code >= 400) return TONES.NEGATIVE;
  if (code >= 200 && code < 300) return TONES.POSITIVE;
  if (code >= 300) return TONES.WARNING;
  return TONES.NEUTRAL;
}
