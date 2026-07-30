import React, { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  FormActions,
  KeyValue,
  PageHeader,
  Spinner,
  Stack,
  Tag,
} from '../design-system';
import { api } from '../api.js';
import { journeyTone, money } from '../status.js';
import JourneySteps from './JourneySteps.jsx';
import SignAgreement from './SignAgreement.jsx';
import SupportTicketForm from './SupportTicketForm.jsx';

// A customer-friendly headline per overall status. Second person, one clause, no
// selling — the customer surface's voice.
const OUTCOME = {
  IN_PROGRESS: {
    title: 'Processing your application',
    sub: 'The bank is reviewing it right now. This page updates itself.',
  },
  COMPLETED: {
    title: 'Approved',
    sub: 'Your card is being set up and will be posted to your address.',
  },
  REJECTED: {
    title: 'Application declined',
    sub: 'We cannot offer you this card right now.',
  },
  REFERRED: {
    title: 'Referred for review',
    sub: 'Someone on our team needs to take a closer look. We will be in touch.',
  },
  FAILED: {
    title: 'Something went wrong',
    sub: 'We could not finish processing your application. Please try again.',
  },
};

/** Step 3: poll the orchestrator and show the live journey to a decision. */
export default function JourneyStatus({ applicationId, product, kind, onRestart, onHome }) {
  const [detail, setDetail] = useState(null);
  const [services, setServices] = useState([]);
  const [error, setError] = useState(null);

  const status = detail?.overallStatus ?? 'IN_PROGRESS';
  const settled = status !== 'IN_PROGRESS';
  // A referral pauses the automated journey, but it is not immutable: the owning service can
  // clear or reject it after human review. Keep watching so the customer sees that resolution
  // without reloading the page. Completed, rejected and failed journeys cannot move again.
  const final = status === 'COMPLETED' || status === 'REJECTED' || status === 'FAILED';

  // The names down the left belong to the journey, not to this page — they are configuration,
  // and a step renamed or reordered must not need a front-end change. Fetched once: they do not
  // move while somebody is watching one application.
  useEffect(() => {
    let live = true;
    api
      .services()
      .then((s) => {
        if (live) setServices(s);
      })
      .catch(() => {
        /* the rail falls back to "Step N" */
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    // STOP only once the journey is irreversibly over. REFERRED remains live because a reviewer
    // can resolve it and resume the flow; the page must reflect that without a manual refresh.
    if (final) return undefined;

    let live = true;
    async function load() {
      try {
        const d = await api.journey(applicationId);
        if (live) {
          setDetail(d);
          setError(null);
        }
      } catch (e) {
        if (live) setError(e.message);
      }
    }
    load();
    const id = setInterval(load, 1000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [applicationId, final]);

  const outcome = OUTCOME[status] ?? OUTCOME.IN_PROGRESS;
  // Demo stepping is holding this application between steps. Say so: a spinner that never
  // resolves is how a working system looks broken, and this is the screen an audience watches.
  const held = detail?.pendingStep != null;
  // The agreement is with the customer. Gated on the journey still running as well as on the
  // flag: a hold cleared by a timeout or an expiry must not leave a live Sign button on a
  // journey that has already ended.
  const signing = !settled && detail?.awaitingSignature === true;
  // Whether this is a card rather than an application is the ORCHESTRATOR's call — only it knows
  // which step is the signature step, and that is configuration. So it is passed in when this
  // screen is opened from the customer's account. Reached straight from the form there is no
  // item yet, and COMPLETED is the one state that is unambiguously a card either way.
  const isProduct = kind === 'PRODUCT' || status === 'COMPLETED';

  return (
    <Stack gap={5}>
      <PageHeader
        title={
          <>
            {!settled && !held && !signing && <Spinner size="lg" label="Processing" />}{' '}
            {signing ? 'Ready for your signature' : outcome.title}
          </>
        }
        badge={
          held ? (
            <Badge tone="warning">WITH AN OPERATOR</Badge>
          ) : signing ? (
            <Badge tone="warning">WAITING FOR YOU</Badge>
          ) : (
            <Badge tone={journeyTone(status)}>{status}</Badge>
          )
        }
        meta={
          <>
            {held
              ? 'Paused at the bank — someone is releasing each step by hand.'
              : signing
                ? 'Everything is approved. Sign your agreement and we will finish setting up your card.'
                : outcome.sub}
            <br />
            Reference <Tag>{applicationId}</Tag>
            {product && ` · ${product.name}`}
            {detail?.requestedLimit ? ` · ${money(detail.requestedLimit)} requested` : ''}
          </>
        }
      />

      <div className="journey-layout">
        <Card title="Your application" subtitle="live" className="journey-layout__rail">
          {detail?.steps?.length ? (
            <JourneySteps
              steps={detail.steps}
              services={services}
              awaitingSignature={signing}
            />
          ) : (
            <EmptyState flush title="Starting…" />
          )}
        </Card>

        <Stack gap={5}>
          {error && <Alert tone="negative">{error}</Alert>}

          {signing && <SignAgreement applicationId={applicationId} />}

          {/* detail is guarded here, not inside YourCard: opened straight from an item, `kind`
              is known synchronously from the click but `detail` is still the async /journey
              fetch's first `null` for one render. Without this YourCard dereferences
              detail.outputs on that frame and the whole tree unmounts blank — no console-visible
              symptom beyond a white screen, which is what "nothing happens on Open" turned out
              to be. */}
          {isProduct && detail && <YourCard detail={detail} applicationId={applicationId} />}

          {/* Support once there is something to ask about: the agreement is with them, or the
              journey has finished one way or another. Not during the automated steps, which take
              seconds and where the only honest answer is "it is being checked". */}
          {(signing || settled) && <SupportTicketForm applicationId={applicationId} />}

        </Stack>
      </div>

      <FormActions>
        <Button variant="primary" onClick={onRestart}>
          Apply for another
        </Button>
        <Button variant="ghost" onClick={onHome}>
          ← Your account
        </Button>
      </FormActions>
    </Stack>
  );
}

/**
 * The card itself, once there is one.
 *
 * <p>Every line comes from a different module's report and any of them may not have arrived yet,
 * so each has its own absent-state rather than one shared "not ready". The card number in
 * particular has no source at all today — {@code panLast4} belongs to the card-issuing module,
 * which has not implemented it — so it reads as pending and will fill itself in the day that
 * changes, with no edit here.</p>
 */
function YourCard({ detail, applicationId }) {
  const outputs = detail.outputs ?? {};
  const settling = detail.overallStatus === 'IN_PROGRESS';
  const [moduleDetails, setModuleDetails] = useState(null);

  useEffect(() => {
    let live = true;
    let attempts = 0;
    let retryId;

    async function loadDetails() {
      attempts += 1;
      try {
        const details = await api.productDetails(applicationId);
        if (!live) return;

        setModuleDetails((current) => mergePresent(current, details));

        // Module callbacks and their own read models are separate commits. A journey can reach
        // COMPLETED a fraction before every read endpoint exposes its row, so keep checking for
        // a short, bounded window instead of turning the first 404/empty response into a
        // permanent blank card.
        if (!hasAllProductDetails(details) && attempts < 15) {
          retryId = window.setTimeout(loadDetails, 2000);
        }
      } catch {
        if (!live) return;
        setModuleDetails((current) => current ?? {});
        if (attempts < 15) retryId = window.setTimeout(loadDetails, 2000);
      }
    }

    loadDetails();
    return () => {
      live = false;
      window.clearTimeout(retryId);
    };
  }, [applicationId]);

  // Saga outputs win as teams adopt the optional outputs callback. The read adapter fills the
  // same customer-safe fields for older modules. Null placeholders must not erase a real value
  // recovered from a module's read model.
  const facts = mergePresent(moduleDetails, outputs);
  const loadingFacts = moduleDetails == null && Object.keys(outputs).length === 0;

  return (
    <Card
      title="Your card"
      subtitle={settling ? 'we are setting it up now' : 'active'}
      headEnd={<Badge tone={settling ? 'info' : 'positive'}>{settling ? 'ON ITS WAY' : 'ACTIVE'}</Badge>}
      foot={
        <Button
          variant="secondary"
          onClick={() => window.open(api.agreementDocumentUrl(applicationId), '_blank')}
        >
          View your agreement
        </Button>
      }
    >
      <KeyValue
        items={[
          ['Product', detail.productCode?.replace('CREDIT_CARD_', '') ?? '—'],
          // The limit GRANTED, never the one asked for — they are different numbers and this is
          // the one that matters.
          ['Credit limit', money(facts.approvedLimit) ?? (loadingFacts ? 'loading…' : 'not supplied')],
          ['APR', facts.apr == null ? (loadingFacts ? 'loading…' : 'not supplied') : `${facts.apr}%`],
          ['Account number', facts.accountId ?? (loadingFacts ? 'loading…' : 'not supplied')],
          [
            'Card number',
            facts.panLast4
              ? `•••• ${facts.panLast4}`
              : loadingFacts
                ? 'loading…'
                : 'awaiting card issuer',
          ],
        ]}
      />
    </Card>
  );
}

function mergePresent(...sources) {
  return sources.reduce((merged, source) => {
    Object.entries(source ?? {}).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') merged[key] = value;
    });
    return merged;
  }, {});
}

function hasAllProductDetails(details) {
  return (
    details?.approvedLimit != null &&
    details?.apr != null &&
    Boolean(details?.accountId) &&
    Boolean(details?.panLast4)
  );
}
