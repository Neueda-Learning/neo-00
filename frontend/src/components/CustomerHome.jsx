import React, { useEffect, useState } from 'react';
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
import { api } from '../api.js';
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
  const [moduleDetails, setModuleDetails] = useState(null);

  useEffect(() => {
    let live = true;
    let attempts = 0;
    let retryId;

    async function loadDetails() {
      attempts += 1;
      try {
        const details = await api.productDetails(item.applicationId);
        if (!live) return;
        setModuleDetails((current) => mergePresent(current, details));

        // The dashboard used to read only the saga callback outputs. Most modules still send
        // the original three-field callback, so their customer-safe facts live behind their
        // case APIs and can arrive just after the product appears here.
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
  }, [item.applicationId]);

  const facts = mergePresent(moduleDetails, outputs);
  const loadingFacts = moduleDetails == null && Object.keys(outputs).length === 0;

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
          [
            'Credit limit',
            money(facts.approvedLimit) ?? (loadingFacts ? 'loading…' : 'not supplied'),
          ],
          [
            'APR',
            facts.apr == null ? (loadingFacts ? 'loading…' : 'not supplied') : `${facts.apr}%`,
          ],
          [
            'Account number',
            facts.accountId ?? (loadingFacts ? 'loading…' : 'not supplied'),
          ],
          [
            'Card number',
            facts.panLast4
              ? `•••• ${facts.panLast4}`
              : loadingFacts
                ? 'loading…'
                : 'awaiting card issuer',
          ],
          ['Opened', time(item.createdAt)],
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
