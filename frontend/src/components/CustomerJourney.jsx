import React, { useState } from 'react';
import {
  AppShell,
  Button,
  TopNav,
} from '../design-system';
import neoLogo from '../assets/cards/neo-logo.png';
import platinumCardImage from '../assets/cards/neo-platinum-card.png';
import premiumCardImage from '../assets/cards/neo-premium-card.png';
import { PRODUCTS } from '../products.js';
import { money } from '../status.js';
import ApplicationForm from './ApplicationForm.jsx';
import JourneyStatus from './JourneyStatus.jsx';

const STEPS = [
  { key: 'product', label: 'Choose a card' },
  { key: 'form', label: 'Your details' },
  { key: 'status', label: 'Decision' },
];

const CARD_IMAGES = {
  premium: premiumCardImage,
  platinum: platinumCardImage,
};

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
      className={`customer-journey customer-journey--${step}`}
      nav={
        <TopNav
          className="customer-nav"
          brand={<CustomerBrand />}
          actions={
            <>
              <Progress step={step} />
              <Button className="customer-nav__home" variant="ghost" size="sm" onClick={onHome}>
                Home
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

function CustomerBrand() {
  return <img className="customer-brand__logo" src={neoLogo} alt="NEO" />;
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
    <section className="product-selection" aria-labelledby="product-selection-title">
      <header className="product-selection__header">
        <h1 id="product-selection-title">Choose your card.</h1>
      </header>

      <div className="product-selection__grid">
        {PRODUCTS.map((p) => (
          <article className="product-option" key={p.code}>
            <img
              className="product-option__image"
              src={CARD_IMAGES[p.accent]}
              alt={`${p.name} in NEO's black and yellow card design`}
            />

            <header className="product-option__header">
              <h2>{p.name}</h2>
              <p>{p.tagline}</p>
            </header>

            <ul className="product-option__features">
              {p.features.map((feature) => (
                <li key={feature}>
                  <span aria-hidden="true">✓</span>
                  {feature}
                </li>
              ))}
            </ul>

            <dl className="product-option__facts">
              <div>
                <dt>Credit limit</dt>
                <dd>{money(p.minLimit)} – {money(p.maxLimit)}</dd>
              </div>
              <div>
                <dt>Representative APR</dt>
                <dd>{p.apr}%</dd>
              </div>
              <div>
                <dt>Minimum income</dt>
                <dd>{money(p.minIncome)}</dd>
              </div>
            </dl>

            <div className="product-option__actions">
              <Button
                className="product-option__apply"
                variant="primary"
                onClick={() => onChoose(p)}
                aria-label={`Apply now for the ${p.name}`}
              >
                Apply Now
              </Button>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
