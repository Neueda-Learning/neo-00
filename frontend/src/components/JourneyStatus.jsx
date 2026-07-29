import React, { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  FormActions,
  PageHeader,
  Spinner,
  Stack,
  Tag,
  Timeline,
} from '../design-system';
import { api } from '../api.js';
import { clock, eventTone, journeyTone, money, stepTone } from '../status.js';
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
export default function JourneyStatus({ applicationId, product, onRestart, onHome }) {
  const [detail, setDetail] = useState(null);
  const [services, setServices] = useState([]);
  const [error, setError] = useState(null);

  const status = detail?.overallStatus ?? 'IN_PROGRESS';
  const settled = status !== 'IN_PROGRESS';

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
    // STOP once the journey is over. There is a form on this screen when it completes, and a
    // poll that keeps running would call setDetail under the customer's typing every second.
    if (settled) return undefined;

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
  }, [applicationId, settled]);

  const outcome = OUTCOME[status] ?? OUTCOME.IN_PROGRESS;
  // Demo stepping is holding this application between steps. Say so: a spinner that never
  // resolves is how a working system looks broken, and this is the screen an audience watches.
  const held = detail?.pendingStep != null;
  // The agreement is with the customer. Gated on the journey still running as well as on the
  // flag: a hold cleared by a timeout or an expiry must not leave a live Sign button on a
  // journey that has already ended.
  const signing = !settled && detail?.awaitingSignature === true;

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

          {status === 'COMPLETED' && <SupportTicketForm applicationId={applicationId} />}

          <Card title="What is happening" subtitle={settled ? 'complete' : 'updated every second'}>
            {!detail && !error && <EmptyState flush title="Loading…" />}
            {detail && detail.events.length === 0 && (
              <EmptyState flush title="Waiting for the first update…" />
            )}
            {detail && detail.events.length > 0 && (
              <Timeline
                items={detail.events.map((e) => ({
                  id: e.id,
                  tone: eventTone(e),
                  title: (
                    <>
                      <Tag>{e.stepIndex > 0 ? `step ${e.stepIndex}` : '—'}</Tag>
                      <span>{e.eventType}</span>
                      {e.serviceId && <Tag>{e.serviceId}</Tag>}
                      {e.status && <Badge tone={stepTone(e.status)}>{e.status}</Badge>}
                    </>
                  ),
                  detail: e.comment,
                  when: clock(e.createdAt),
                }))}
              />
            )}
          </Card>
        </Stack>
      </div>

      <FormActions>
        <Button variant="primary" onClick={onRestart}>
          Apply for another
        </Button>
        <Button variant="ghost" onClick={onHome}>
          ← Home
        </Button>
      </FormActions>
    </Stack>
  );
}
