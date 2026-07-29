import React from 'react';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Grid,
  KeyValue,
  PageHeader,
  Stack,
  Tag,
} from '../design-system';
import { productByCode } from '../products.js';
import { journeyTone, money, time } from '../status.js';

/**
 * Everything one customer has: the cards they hold, and the applications still in flight or
 * refused.
 *
 * <p>The split is the orchestrator's — an item is a PRODUCT once the agreement is signed and the
 * journey is alive or finished, and an APPLICATION otherwise, including one that died after
 * signing. Only the orchestrator knows which step is the signature step, so the rule lives there
 * and this screen just reads `kind`.
 *
 * Cards rather than a table: `DataTable` caps at ten rows and truncates silently, which is the
 * wrong behaviour for "everything you have".
 */
export default function CustomerHome({ customerId, items = [], onOpen, onApply }) {
  const products = items.filter((i) => i.kind === 'PRODUCT');
  const applications = items.filter((i) => i.kind === 'APPLICATION');

  return (
    <Stack gap={6}>
      <PageHeader
        title="Your account"
        lede={
          items.length
            ? 'everything you hold with us, and anything still being decided'
            : 'you have not applied for anything yet'
        }
        badge={<Tag>{customerId}</Tag>}
        actions={
          <Button variant="primary" onClick={onApply}>
            Apply for {items.length ? 'another card' : 'a card'}
          </Button>
        }
      />

      {items.length === 0 && (
        <EmptyState
          title="Nothing here yet"
          action={
            <Button variant="primary" onClick={onApply}>
              Apply for a card
            </Button>
          }
        >
          Choose a card and we will take you through the application.
        </EmptyState>
      )}

      {products.length > 0 && (
        <section>
          <h2 className="customer-home__heading">Your products</h2>
          <Grid cols="auto" min={340}>
            {products.map((item) => (
              <ProductCard key={item.applicationId} item={item} onOpen={onOpen} />
            ))}
          </Grid>
        </section>
      )}

      {applications.length > 0 && (
        <section>
          <h2 className="customer-home__heading">Your applications</h2>
          <Grid cols="auto" min={340}>
            {applications.map((item) => (
              <ApplicationCard key={item.applicationId} item={item} onOpen={onOpen} />
            ))}
          </Grid>
        </section>
      )}
    </Stack>
  );
}

/** A card the customer holds. */
function ProductCard({ item, onOpen }) {
  const product = productByCode(item.productCode);
  const outputs = item.outputs ?? {};
  const settling = item.overallStatus === 'IN_PROGRESS';

  return (
    <Card
      title={product?.name ?? item.productCode}
      subtitle={settling ? 'being set up' : 'active'}
      headEnd={settling ? <Badge tone="info">ON ITS WAY</Badge> : <Badge tone="positive">ACTIVE</Badge>}
      foot={
        <Button variant="secondary" onClick={() => onOpen(item)}>
          Open
        </Button>
      }
    >
      <KeyValue
        items={[
          // Each of these comes from a different module and any of them may not have reported
          // yet, so each has its own absent-state rather than one shared "not ready".
          ['Credit limit', money(outputs.approvedLimit) ?? 'being confirmed'],
          ['Account number', outputs.accountId ?? 'being set up'],
          // panLast4 is neo-08's to report and that module has not implemented it yet, so this
          // reads as pending rather than missing — and lights up the day it does.
          ['Card number', outputs.panLast4 ? `•••• ${outputs.panLast4}` : 'issued, number to follow'],
          ['Opened', time(item.createdAt)],
        ]}
      />
    </Card>
  );
}

/** An application still being decided, waiting on the customer, or refused. */
function ApplicationCard({ item, onOpen }) {
  const product = productByCode(item.productCode);
  const said = describe(item);

  return (
    <Card
      title={product?.name ?? item.productCode}
      subtitle={said.sub}
      headEnd={<Badge tone={said.tone}>{said.label}</Badge>}
      foot={
        <Button variant={said.act ? 'primary' : 'secondary'} onClick={() => onOpen(item)}>
          {said.act ?? 'Open'}
        </Button>
      }
    >
      <KeyValue
        items={[
          ['Applied for', money(item.requestedLimit) ?? '—'],
          ['Started', time(item.createdAt)],
        ]}
      />
    </Card>
  );
}

/**
 * What an application's state means to the person who made it. The words match
 * `JourneyStatus`'s own OUTCOME map — the same journey must not be described two different ways
 * depending on which screen you are looking at.
 */
function describe(item) {
  if (item.awaitingSignature) {
    return {
      label: 'WAITING FOR YOU',
      tone: 'warning',
      sub: 'your agreement is ready to sign',
      act: 'Sign your agreement',
    };
  }
  if (item.pendingStep != null) {
    return { label: 'WITH AN OPERATOR', tone: 'warning', sub: 'paused at the bank' };
  }
  switch (item.overallStatus) {
    case 'COMPLETED':
      return { label: 'APPROVED', tone: 'positive', sub: 'approved' };
    case 'REJECTED':
      return { label: 'DECLINED', tone: 'negative', sub: 'we could not offer you this card' };
    case 'REFERRED':
      return { label: 'REFERRED', tone: 'warning', sub: 'someone is taking a closer look' };
    case 'FAILED':
      return { label: 'FAILED', tone: 'negative', sub: 'something went wrong' };
    default:
      return {
        label: 'IN PROGRESS',
        tone: journeyTone('IN_PROGRESS'),
        sub: 'being reviewed right now',
      };
  }
}
