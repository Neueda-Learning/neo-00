import React, { useState } from 'react';
import {
  AppShell,
  Button,
  Card,
  Grid,
  KeyValue,
  PageHeader,
  TopNav,
} from '../design-system';
import { PRODUCTS } from '../products.js';
import { money } from '../status.js';
import ApplicationForm from './ApplicationForm.jsx';
import JourneyStatus from './JourneyStatus.jsx';

const STEPS = [
  { key: 'product', label: 'Choose a card' },
  { key: 'form', label: 'Your details' },
  { key: 'status', label: 'Decision' },
];

/**
 * The Customer Journey Simulation: a three-step flow an attendee drives themselves —
 * pick a product, fill the application, then watch the live decision come back.
 *
 * This is the one customer-facing surface in the project. It uses the same design
 * system as the operator screens; what changes is the voice, not the components.
 */
export default function CustomerJourney({ onHome }) {
  const [step, setStep] = useState('product');
  const [product, setProduct] = useState(null);
  const [applicationId, setApplicationId] = useState(null);

  return (
    <AppShell
      nav={
        <TopNav
          brand="NEO"
          product="Apply for a card"
          actions={
            <>
              <Progress step={step} />
              <Button variant="ghost" size="sm" onClick={onHome}>
                ← Home
              </Button>
            </>
          }
        />
      }
    >
      {step === 'product' && (
        <ProductStep
          onChoose={(p) => {
            setProduct(p);
            setStep('form');
          }}
        />
      )}

      {step === 'form' && (
        <ApplicationForm
          product={product}
          onBack={() => setStep('product')}
          onSubmitted={(id) => {
            setApplicationId(id);
            setStep('status');
          }}
        />
      )}

      {step === 'status' && (
        <JourneyStatus
          applicationId={applicationId}
          product={product}
          onRestart={() => {
            setProduct(null);
            setApplicationId(null);
            setStep('product');
          }}
          onHome={onHome}
        />
      )}
    </AppShell>
  );
}

/** "Choose a card › Your details › Decision" — where the customer is, in the bar. */
function Progress({ step }) {
  const current = STEPS.findIndex((s) => s.key === step);
  return (
    <ol className="journey-progress">
      {STEPS.map((s, i) => (
        <li
          key={s.key}
          className={[
            'journey-progress__step',
            i === current && 'journey-progress__step--current',
            i < current && 'journey-progress__step--done',
          ]
            .filter(Boolean)
            .join(' ')}
          aria-current={i === current ? 'step' : undefined}
        >
          <span className="journey-progress__num">{i + 1}</span>
          <span>{s.label}</span>
        </li>
      ))}
    </ol>
  );
}

/** Step 1: the products, side by side. */
function ProductStep({ onChoose }) {
  return (
    <>
      <PageHeader
        title="Choose your card"
        lede="two cards, two tiers — pick one to start your application"
      />
      <Grid cols="auto" min={360}>
        {PRODUCTS.map((p) => (
          <Card key={p.code} bodyless>
            <div className={`product-face product-face--${p.accent}`}>
              <span className="product-face__brand">Neo</span>
              <span className="product-face__chip" aria-hidden="true" />
              <span className="product-face__name">{p.name}</span>
            </div>
            <div style={{ padding: 'var(--ds-space-5)' }}>
              <p>{p.tagline}</p>
              <ul className="product-features">
                {p.features.map((f) => (
                  <li key={f}>{f}</li>
                ))}
              </ul>
              <KeyValue
                items={[
                  ['Credit limit', `${money(p.minLimit)} – ${money(p.maxLimit)}`],
                  ['Representative APR', `${p.apr}%`],
                  ['Minimum income', money(p.minIncome)],
                ]}
              />
              <Button
                variant="primary"
                block
                onClick={() => onChoose(p)}
                style={{ marginTop: 'var(--ds-space-5)' }}
              >
                Choose {p.name}
              </Button>
            </div>
          </Card>
        ))}
      </Grid>
    </>
  );
}
