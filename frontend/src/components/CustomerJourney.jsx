import React, { useEffect, useState } from 'react';
import {
  AppShell,
  Button,
  Card,
  Grid,
  KeyValue,
  PageHeader,
  TopNav,
} from '../design-system';
import { api } from '../api.js';
import { PRODUCTS, productByCode, withLiveCatalogue } from '../products.js';
import { money } from '../status.js';
import ApplicationForm from './ApplicationForm.jsx';
import CustomerHome from './CustomerHome.jsx';
import JourneyStatus from './JourneyStatus.jsx';

const STEPS = [
  { key: 'product', label: 'Choose a card' },
  { key: 'form', label: 'Your details' },
  { key: 'status', label: 'Decision' },
];

/**
 * Everything a signed-in customer sees: what they already have, and the flow to get more —
 * pick a product, fill the application, then watch the live decision come back.
 *
 * This is the one customer-facing surface in the project. It uses the same design
 * system as the operator screens; what changes is the voice, not the components.
 *
 * <p>The shell lives here rather than higher up because the customer's code and the Logout
 * button belong on every one of these screens, and this is the component that owns the bar.</p>
 */
export default function CustomerJourney({ customerId, initialItems = [], onLogout }) {
  // Somebody who already has something lands on it; somebody new goes straight to the cards,
  // because an empty list is not worth a screen of its own.
  const [step, setStep] = useState(initialItems.length ? 'home' : 'product');
  const [items, setItems] = useState(initialItems);
  const [product, setProduct] = useState(null);
  const [applicationId, setApplicationId] = useState(null);
  // Whose call it is that something is a card and not an application: the orchestrator's.
  const [kind, setKind] = useState(null);

  /** Re-read what this customer has. Cheap: one database read, no module calls. */
  async function refresh() {
    try {
      const view = await api.customer(customerId);
      setItems(view.items);
      return view.items;
    } catch {
      return items;   // a failed refresh must not empty a screen that was already right
    }
  }

  async function backToHome() {
    const fresh = await refresh();
    setProduct(null);
    setApplicationId(null);
    setKind(null);
    setStep(fresh.length ? 'home' : 'product');
  }

  return (
    <AppShell
      nav={
        <TopNav
          brand="NEO"
          product="Your bank"
          actions={
            <>
              {/* The apply rail means nothing over a list of things you already own. */}
              {step !== 'home' && <Progress step={step} />}
              <Tagged code={customerId} />
              <Button variant="ghost" size="sm" onClick={onLogout}>
                Log out
              </Button>
            </>
          }
        />
      }
    >
      {step === 'home' && (
        <CustomerHome
          customerId={customerId}
          items={items}
          onApply={() => setStep('product')}
          onOpen={(item) => {
            setApplicationId(item.applicationId);
            setKind(item.kind);
            // An item opened from home has a product CODE, not the marketing object the picker
            // hands over. Look it up, so the detail screen reads the same either way.
            setProduct(productByCode(item.productCode) ?? null);
            setStep('status');
          }}
        />
      )}

      {step === 'product' && (
        <ProductStep
          onChoose={(p) => {
            setProduct(p);
            setStep('form');
          }}
          onBack={items.length ? () => setStep('home') : undefined}
        />
      )}

      {step === 'form' && (
        <ApplicationForm
          product={product}
          customerId={customerId}
          onBack={() => setStep('product')}
          onSubmitted={(id) => {
            setApplicationId(id);
            setKind(null);
            setStep('status');
          }}
        />
      )}

      {step === 'status' && (
        <JourneyStatus
          applicationId={applicationId}
          product={product}
          kind={kind}
          onRestart={() => {
            setProduct(null);
            setApplicationId(null);
            setKind(null);
            setStep('product');
          }}
          onHome={backToHome}
        />
      )}
    </AppShell>
  );
}

/** The signed-in code, in the bar. */
function Tagged({ code }) {
  return <span className="customer-code" title="your customer code">{code}</span>;
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
function ProductStep({ onChoose, onBack }) {
  // The codes and limit ranges belong to the verification module, not to us, and it rejects an
  // application that disagrees with them. Read them; keep the built-in copy if it cannot be asked.
  //
  // The copy is the INITIAL state, not an error state, and that is what makes this safe to do on
  // mount: the cards are on screen before the request is made, so a slow module delays an update
  // rather than an empty page. Measured — module stopped, 0.4s; module hung, 5s (the orchestrator's
  // read timeout), both ending in the same two cards nobody saw flicker.
  const [products, setProducts] = useState(PRODUCTS);

  useEffect(() => {
    let live = true;
    api
      .products()
      .then((entries) => {
        if (live) setProducts(withLiveCatalogue(entries));
      })
      .catch(() => {
        /* the fallback list is already in state */
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <>
      <PageHeader
        title="Choose your card"
        lede="two cards, two tiers — pick one to start your application"
        actions={
          onBack && (
            <Button variant="ghost" onClick={onBack}>
              ← Back to your account
            </Button>
          )
        }
      />
      <Grid cols="auto" min={360}>
        {products.map((p) => (
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
