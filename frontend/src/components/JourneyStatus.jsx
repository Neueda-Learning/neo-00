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
  const [error, setError] = useState(null);

  useEffect(() => {
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
  }, [applicationId]);

  const status = detail?.overallStatus ?? 'IN_PROGRESS';
  const outcome = OUTCOME[status] ?? OUTCOME.IN_PROGRESS;
  const settled = status !== 'IN_PROGRESS';
  // Demo stepping is holding this application between steps. Say so: a spinner that never
  // resolves is how a working system looks broken, and this is the screen an audience watches.
  const held = detail?.pendingStep != null;

  return (
    <Stack gap={5}>
      <PageHeader
        title={
          <>
            {!settled && !held && <Spinner size="lg" label="Processing" />} {outcome.title}
          </>
        }
        badge={
          held ? (
            <Badge tone="warning">WITH AN OPERATOR</Badge>
          ) : (
            <Badge tone={journeyTone(status)}>{status}</Badge>
          )
        }
        meta={
          <>
            {held ? 'Paused at the bank — someone is releasing each step by hand.' : outcome.sub}
            <br />
            Reference <Tag>{applicationId}</Tag>
            {product && ` · ${product.name}`}
            {detail?.requestedLimit ? ` · ${money(detail.requestedLimit)} requested` : ''}
          </>
        }
      />

      <Card title="What is happening" subtitle="live, updated every second">
        {error && <Alert tone="negative">{error}</Alert>}
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
